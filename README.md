# 实验运行谱系库（Experiment Run Lineage）

Kotlin + Ktor + SQLite 的实验运行谱系库：记录数据集摘要、规范化参数、环境声明、
阶段检查点与父子派生关系，回答“**结果能否从相同输入重现**”。系统不执行真实重计算，
阶段产物由内置的确定性阶段模拟器（deterministic-stage-simulator）生成。

## 安装

```bash
./gradlew build -x test
```

## 演示

```bash
./gradlew test
./gradlew run --args='--port 5241'
```

打开 http://localhost:5241 ，页面标题为「实验运行谱系库」。空库首次启动会预置：

- `tpl-demo-standard`：`ingest → transform → aggregate` 三阶段模板，第三阶段声明依赖 `BLAS_VERSION`；
- `run-demo-parent`：基线父运行，三个阶段全部成功；
- `run-demo-child-env`：派生自父运行、仅把 `BLAS_VERSION` 从 `openblas-0.3.21` 改为
  `openblas-0.3.27`。子运行**复用前两阶段**（ingest/transform 不声明该环境变量），
  **因环境变化重跑第三阶段** aggregate。

命令行参数：`--port`（默认 8080）、`--db`（默认 `./lineage.db`）、`--no-seed`（不写预置数据）。

## 规范化规则（同一参数不产生不同身份）

规范化文本是紧凑 JSON，SHA-256 即身份，规则集中在 `src/main/kotlin/lineage/Canonical.kt`
与 `Normalizer.kt`：

- **map 键顺序**：所有对象键按 Unicode 码位排序后递归输出；
- **无关空白**：字符串去首尾空白，内部连续空白折叠为一个空格；
- **浮点文本等价**：使用最短往返十进制文本（`0.0010`、`1e-3`、`0.001` 归一为 `0.001`），
  `-0.0` 归一为 `0`；
- **NaN / Infinity**：JSON 文本扫描与数字类型两道防线拒绝（`-Infinity` 同样拒绝）；
- **缺字段**：必填缺失拒绝；可选缺失显式编码为 `null`；
- **未声明字段**：拒绝；
- **secret 参数**：值不进规范化参数（编码为固定占位 `"__secret__"`），真实值只用于
  计算 `secretDigests`（`sha256("secret:" + 规范化值)`）单独保存；导出 manifest 时
  参数值不出现，仅保留摘要。

## 冻结、推进与崩溃恢复

`POST /api/runs` 冻结运行时立即固定：模板版本、参数规范化结果、输入数据摘要、环境指纹。

每个阶段严格按顺序推进（`POST /api/runs/{id}/advance`）：

1. 独立事务先写 **intent**（阶段输入哈希、上游链哈希、阶段环境指纹）并提交；
2. 独立事务写**产物摘要 + receipt**（内容寻址，contentHash 幂等）；
3. 最后以乐观锁 `UPDATE ... WHERE version = ?` **推进游标**。

因此进程在任意位置退出后恢复：

- intent 已落盘、产物未写 → 恢复时不重复 intent，只补产物；
- 已成功产物 → 内容寻址去重，不重复产生；
- `?crashAfterIntent=true`（表单上为“模拟 intent 后进程退出”）用于演示该恢复路径。

`POST /api/runs/{id}/fail` 向当前阶段注入失败：落 `failed` 检查点并把运行置为终态失败。
**失败检查点永远不会被派生运行当作成功缓存复用**（复用规划显式排除）。

## 派生复用规则

`POST /api/runs/derive` 记录 `parentRunId` 与参数差异（旧值 → 新值）。检查点复用必须
同时满足：

1. 输入数据摘要一致；
2. 阶段名 + 阶段版本一致；
3. 上游产物链一致（一旦某阶段重跑，其后阶段自动不能复用）；
4. 阶段声明的环境子集指纹一致——**环境指纹变化阻止复用并在页面/API 给出具体原因**；
5. 阶段声明的参数子集一致；
6. 父检查点必须是成功检查点。

## 并发与幂等

- 两个页面同时推进同一运行：请求携带当前乐观版本 `expectedVersion`，先提交者成功，
  后提交者收到 **409 版本冲突**（JSON API 与表单页面分别返回 JSON 错误 / 中文错误页）；
- 相同 `idempotencyKey` 且内容一致返回原运行；同键不同内容返回 409。

## 可重现性与谱系

- `GET /api/runs/{id}/verify`：本地检查点用相同输入重算模拟器并比对 contentHash；
  导入的检查点按记录的阶段输入哈希与上游链做谱系校验；
- `GET /api/runs/{id}/lineage` 与运行详情页：沿最终结果回溯每个父运行、参数差异、
  环境指纹与每阶段产物摘要。

## Manifest 导出 / 重放

- `GET /api/manifest/export?runId=...`（可重复或省略导出全部）；
- `POST /api/manifest/import`：重新导入模板、运行、阶段与内容寻址产物并执行校验；
  重复导入幂等跳过；
- manifest **不包含** secret 参数值，仅含 `secretDigests` 与 `redactedSecrets` 名单。

## 主要 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/templates` | 列出 / 创建模板（同名改动产生新版本） |
| GET/POST | `/api/runs` | 列出 / 冻结运行（支持 `idempotencyKey`） |
| GET | `/api/runs/{id}` | 运行详情（含全部检查点） |
| POST | `/api/runs/{id}/advance` | 推进一阶段（`expectedVersion`、`crashAfterIntent`） |
| POST | `/api/runs/{id}/fail` | 向当前阶段注入失败 |
| POST | `/api/runs/derive` | 派生运行 |
| GET | `/api/runs/{id}/verify` | 可重现性校验 |
| GET | `/api/runs/{id}/lineage` | 谱系回溯 |
| GET/POST | `/api/manifest/export`、`/api/manifest/import` | 导出 / 重放 |

网页表单路由位于 `/forms/...`，JSON 请求体统一严格解析并做非有限数值扫描。

## 测试

`src/test/kotlin/lineage/LineageTest.kt` 覆盖：参数规范化、环境变化阻止复用、
intent 后恢复不重复产物、失败检查点不入缓存、派生复用与血缘回溯、并发推进版本冲突、
幂等创建、导出重放（含 secret 不泄漏）以及预置父子运行“复用前两阶段 + 环境变化重跑
第三阶段”的端到端断言。

## 技术栈

Kotlin 2.4.20 · Ktor 3.6.0（Netty）· sqlite-jdbc 3.53.4.0 · kotlinx.serialization ·
Gradle 9.8.0 wrapper（JDK 25 工具链）。
