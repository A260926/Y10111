# 实验运行谱系库（Experiment Run Lineage）

Kotlin + Ktor + SQLite 的实验运行谱系库：记录数据集摘要、参数、环境声明、阶段检查点与
派生关系，回答「结果能否从相同输入重现」。系统**不执行真实重计算**，使用内置的确定性阶段
模拟器。

## 能力概览

- **实验模板**：阶段定义（名称、阶段版本、消费参数键、是否环境敏感）与 secret 参数键；
  模板版本在运行冻结时固定，之后不可变。
- **冻结运行**：启动即固定模板版本、参数规范化结果、输入数据摘要（SHA-256）与环境指纹。
- **阶段推进**：严格按「先写 intent → 事务外物化 → 写产物摘要与 receipt → 推进游标」。
  进程在任意位置退出后恢复：不重复产物；FAILED 检查点永远不会被当作成功缓存。
- **注入失败**：
  - `crash=false`：在 intent 之后物化失败，写入 FAILED 检查点（无产物），重试 attempt+1；
  - `crash=true`：模拟进程在 intent 落盘后被杀（无 FAILED、无产物），再推进按同一 intent 恢复。
- **派生运行**：记录父运行与改动参数；只复用「输入摘要、阶段版本、作用域参数、上游产物链
  全部一致」的检查点；环境敏感阶段环境指纹变化会阻止复用并给出具体原因。
- **重现性验证**：从冻结的输入/参数/环境用确定性模拟器重算每个自算阶段，逐字节比对产物与
  receipt（含产物链与 receipt 哈希链）；复用阶段做结构校验。
- **导出/导入 manifest**：可在空库重新导入并验证谱系；被标记为 secret 的参数值永不进入
  manifest（以 `<redacted-secret>` 占位并在 `redactedSecretKeys` 声明）。
- **幂等键**：相同幂等键 + 相同内容返回原运行；相同键但内容不同返回 409。
- **并发**：两个页面同时推进同一阶段，恰好一个成功，另一个得到 409 版本冲突；
  也支持基于 `cursorVersion` 的乐观并发检查。

## 参数规范化规范

身份 = 规范化 JSON 的 UTF-8 SHA-256：

1. 仅接受标准 JSON；`NaN`、`Infinity`、`-Infinity` 一律拒绝（必须显式编码为字符串/null，
   或缺字段明确报错）。
2. 对象键按 UTF-16 码元字典序排序；重复键拒绝；解析时键顺序不影响身份。
3. 规范化文本无任何无关空白，空白差异不影响身份。
4. 数字以 BigDecimal 规范化：`1` = `1.0` = `1.00` = `1e0`；`-0` 与 `0.0` 收敛为 `0`。
5. secret 顶层键必须存在；且 secret 键不得被任何阶段消费（模板创建时强制）。

## 页面

- `GET /` 运行总览（创建模板、冻结运行、列出运行）
- `GET /templates` 模板与阶段定义
- `GET /runs/{id}` 运行详情：参数、阶段检查点、推进/注入失败/派生、重现性结论、谱系回溯
- `GET /import` 上传 manifest 导入并验证
- 每个页面标题均包含「实验运行谱系库」

## JSON API

- `POST /api/templates`
- `POST /api/runs/freeze`、`POST /api/runs/derive`
- `POST /api/runs/{id}/advance?cursorVersion=<可选>`
- `POST /api/runs/{id}/inject-failure?stage=<i>&crash=<true|false>&armOnly=<true|false>`
- `GET /api/runs/{id}`、`GET /api/runs/{id}/verify`、`GET /api/runs/{id}/lineage`
- `GET /api/export`、`POST /api/import`

## 预置演示谱系

首次启动空库时自动播种：

- 模板 `tpl_image_pipeline`：`extract@1`（环境不敏感）、`transform@2`（环境不敏感）、
  `evaluate@3`（环境敏感）。
- `run_parent_demo` 在 `cluster.id=cluster-a` 完整跑完三阶段。
- `run_child_demo` 派生自父运行：改了不被阶段消费的 `batch_tag`，
  且 `cluster.id=cluster-b` —— **前两阶段复用父检查点，第三阶段因环境指纹变化重跑**。

## 构建与运行

```bash
./gradlew build -x test                 # 安装/构建（跳过测试）

./gradlew test                          # 测试：规范化、环境变化、intent 后恢复、
                                        # 失败缓存、派生复用、并发推进、导出重放、幂等
./gradlew run --args='--port 5241'      # 演示运行
# 然后打开 http://127.0.0.1:5241/

LINEAGE_DB=/path/to/demo.db ./gradlew run --args='--port 5241'   # 自定义数据库文件
```

## 存储

SQLite（WAL，外键开启，`BEGIN IMMEDIATE` 事务 + busy_timeout）。
表：`templates`、`runs`、`stages`（检查点）、`events`（intent/成功/失败/派生审计）、`meta`。
