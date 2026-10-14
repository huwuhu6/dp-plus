# 消费决策 Agent

面向本地餐饮消费场景的多轮决策系统。用户可以通过自然语言提出推荐、追问商户评价、优惠券、营业时间和备选方案等问题；系统结合对话状态、结构化事实、评价证据和向量检索生成可追溯的建议。

## 核心能力

- V2 语义决策：模型只输出结构化用户语义；实体 grounding、任务生命周期、搜索计划和 durable 状态变更均由 Java 确定性逻辑处理，避免模型生成数据库身份或执行计划。

- 多轮对话：支持普通闲聊、进入消费决策、在决策中补充位置/预算、自然退出后再次进入。
- 位置优先：每次餐饮推荐默认请求当前位置；当前未配置地理编码服务时不接受地点名直接换算坐标，避免像“鼓楼”这类歧义地名被猜测或错误映射。
- 工具编排：围绕商户详情、评价证据、优惠券、备选商户等事实工具回答追问，并保存工具调用审计。
- 状态与记忆：`ai_chat_message` 持久化聊天记录，`ai_chat_session` 持久化位置、当前商户和已展示候选等会话槽位；Redis 作为低延迟缓存而不是唯一记忆来源。
- 混合检索：MySQL 负责菜系、预算、距离、营业时间等硬约束；Milvus 只在硬筛选候选白名单内进行语义召回和重排，不会突破地理或品类约束。
- 可解释输出：响应包含决策状态、候选、事实证据、处理 trace 和延迟指标；日志记录模型、工具、状态机和语义召回摘要。
- 离线评测：内置单轮检索评测与多轮对话轨迹评测，覆盖 Recall@K、MRR、证据覆盖率、约束违例、工具覆盖率、模型用量和延迟，支持运行间比较。

## 技术栈

- JDK 21、Spring Boot 3.4、MyBatis-Plus、MySQL、Redis
- Spring AI 1.0：OpenAI-compatible EmbeddingModel 与 Milvus VectorStore
- Milvus：商户画像和评价证据的向量存储
- 兼容 OpenAI 协议的聊天模型与 Embedding 服务；开发环境已验证百炼 `text-embedding-v4`

> 当前并非所有模型调用都由 Spring AI 承担。Spring AI 用于 embedding 和 Milvus 向量检索；V2 `SemanticInterpreter` 通过项目内 `OpenAiCompatibleClient` 使用受限 schema 解析用户语义。检索、计划编译、工具绑定和状态归约仍由 Java 业务代码确定性执行。

## 地点解析（高德 MCP）

地点文本不会通过硬编码坐标解析。启用高德 MCP 后，用户在定位澄清阶段输入“福州鼓楼”“上街大学城”等地点，后端调用 MCP `maps_geo`，展示解析候选；用户确认候选后才写入会话位置槽位并进行半径检索。

1. 复制 `.env.example` 为本地 `.env`，填入 `AMAP_MAPS_API_KEY`。`.env` 已被 Git 忽略。
2. 执行 `docker compose -f docker-compose.amap-mcp.yml up -d --build`，MCP 服务会暴露在 `http://127.0.0.1:8000/sse`。
3. 为 Spring Boot 进程设置 `AI_LOCATION_MCP_ENABLED=true`；默认 URL 已是本地 `8000`，远程部署时设置 `AI_LOCATION_MCP_URL`。

MCP 不可用、解析为空或用户不确认候选时，不会猜测城市或坐标。用户仍可授权浏览器定位，或显式选择全城搜索。

## 检索架构

```text
用户问题
  -> V2 结构化语义解析与引用 grounding
  -> 任务状态归约 / 位置解析
  -> MySQL 硬过滤（位置、半径、预算、菜系、营业时间）
  -> Milvus 语义召回（仅对硬过滤白名单聚合评分）
  -> 业务重排与证据组装
  -> 可解释回答、trace、工具审计与会话记忆
```

## 本地启动

前端由 Nginx 提供，访问 `http://localhost:8080/`；Agent 调试页为
`http://localhost:8080/agent-console.html`

`8081` 是 Spring Boot 后端 API 端口，
不提供首页且会受到业务登录拦截，不能作为前端访问地址。

### 1. 前置条件

- JDK 21
- Maven 3.9+
- MySQL 8、Redis
- Docker Desktop（启用 Milvus 时）
- 一个聊天模型 API Key，以及一个 OpenAI-compatible Embedding API Key

### 2. 配置本地环境

仓库仅提交 [application.yaml](src/main/resources/application.yaml) 的占位符，不提交密钥。创建本地文件 `src/main/resources/application-dev.yml`，或在运行环境设置等价环境变量。

聊天模型客户端默认最多尝试 3 次，采用指数退避与 Full Jitter（初始 500ms、退避上限 5s），尊重 `Retry-After`，重试预算为 90s；连接等待最多 5s，单次读取超时在预算将耗尽时缩短。通过 `AI_MODEL_MAX_ATTEMPTS`、`AI_MODEL_RETRY_INITIAL_DELAY_MS`、`AI_MODEL_RETRY_MAX_DELAY_MS`、`AI_MODEL_RETRY_MAX_ELAPSED_MS` 配置，尝试次数设为 1 可关闭重试。只重试临时传输/服务错误，不重跑业务轮、工具或成功响应后的语义判断；普通 400、认证失败不重试，已确认的服务端 grammar 编译超时 400 单独处理。每次实际尝试均记录成功/失败，真实语义试点另保存 `transportAttempts`，恢复成功不会抹掉首次失败。

最小配置示例：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your-password
  redis:
    host: 127.0.0.1
    port: 6379

ai:
  base-url: https://api.example.com
  api-key: ${CHAT_API_KEY}
  model: your-chat-model

  retrieval:
    vector-enabled: true
    semantic-weight: 18
    semantic-top-k: 80
    semantic-min-score: 0.35

spring.ai:
  vectorstore:
    type: milvus
    milvus:
      collection-name: hmdp_shop_evidence
      embedding-dimension: 1024
      initialize-schema: true
      auto-id: false
      client:
        host: 127.0.0.1
        port: 19530
  openai:
    # Spring AI appends /v1/embeddings to this base URL.
    base-url: https://dashscope.aliyuncs.com/compatible-mode
    api-key: ${DASHSCOPE_API_KEY}
    embedding:
      options:
        model: text-embedding-v4
        dimensions: 1024
```

`application-dev.yml` 已在 `.gitignore` 中。不要把密钥提交到仓库。

### 3. 初始化数据库

基础业务表和 AI 表的 SQL 位于 `src/main/resources/db/migration/`。当前开发库已由 Flyway 接管：已有数据库在首次启动时以 V27 建立基线，后续 V28 及以上迁移会自动执行并写入 `flyway_schema_history`。

对于全新的空数据库，当前仍处于迁移体系过渡期：需要先按数字版本顺序导入 `V1` 到 `V27`，再启动服务交由 Flyway 管理后续增量迁移。不要在已有业务数据的数据库上重复执行旧版本脚本。

- `V16__agent_business_demo_data.sql`：补充杭州演示餐饮数据、画像、评价证据、券和探店数据。
- `V17_fuzhou_data.sql`：补充福州/闽侯/上街大学城演示数据。

所有 `LOCAL_DEMO` 评价均为本地演示数据，不能表述为真实线上评价。

### 4. 启动 Milvus

```powershell
docker compose -f docker-compose.milvus.yml up -d
docker ps
```

默认暴露 Milvus gRPC 端口 `19530` 和健康检查端口 `9091`。本地数据由 Docker volume `milvus-data` 持久化。

### 5. 启动服务并建立索引

```powershell
mvn -q spring-boot:run

# 新建或重置向量库后执行一次
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8081/ai/retrieval/indexes/rebuild
```

索引重建会从 `ai_shop_profile` 和 `ai_review_document` 读取数据，使用稳定文档 ID 写入 Milvus。百炼 `text-embedding-v4` 单次最多接受 10 条文本，项目已按 10 条自动分批。成功响应示例：

```json
{"success":true,"data":{"documentCount":480}}
```

## 新机器与数据迁移

商户和证据数据以 MySQL 为事实源，Milvus 只是可重建的检索索引：

- 新机器使用空的 Milvus：导入 MySQL 数据后，启动服务并调用一次索引重建接口；需要重新 embedding。
- 迁移或复用同一个 Milvus Docker volume/远程 Milvus collection：不需要重新 embedding；只要 embedding 模型、维度和 collection 名称保持一致。
- 修改 embedding 模型、向量维度、画像/评价文本拼接规则，或批量更新证据数据：应使用新 collection 或清理旧 collection 后重新建立索引。

推荐在开发阶段将 MySQL 数据库备份和 Milvus volume 一起保存；生产环境则将 Milvus 部署为独立服务并配置备份策略。

## 调试与评测接口

| 用途 | 接口 |
| --- | --- |
| Chatbot 对话 | `POST /ai/chat/messages` |
| Chatbot SSE 对话 | `POST /ai/chat/messages/stream` |
| 创建一次决策 | `POST /ai/decisions` |
| 决策中的选项/补充 | `POST /ai/decisions/{sessionId}/messages` |
| 已完成推荐的商户追问 | `POST /ai/decisions/{sessionId}/conversations`，请求体必须包含 `chatId` 与 `message` |
| 重建向量索引 | `POST /ai/retrieval/indexes/rebuild` |
| 运行主评测集 | `POST /ai/evaluations/runs` |
| 运行保留集 | `POST /ai/evaluations/runs/holdout` |
| 对比两次评测 | `GET /ai/evaluations/runs/{runId}/compare/{baselineRunId}` |
| 运行多轮主评测集 | `POST /ai/evaluations/conversation-runs` |
| 运行多轮保留集 | `POST /ai/evaluations/conversation-runs/holdout` |
| 对比两次多轮评测 | `GET /ai/evaluations/conversation-runs/{runId}/compare/{baselineRunId}` |
| 查看多轮失败诊断 | `GET /ai/evaluations/conversation-runs/{runId}/diagnostics` |

多轮评测通过每轮 `expectedV2Outcomes` 检查 V2 Task、criteria、SearchAnchor 与执行结果。`application.yaml` 的默认版本为 `conversation-v2-main-v1`（Java 配置类的独立缺省值为 `conversation-v2-runtime-v1`）；开展当前主回归前应显式设置 `AI_CONVERSATION_EVALUATION_DATASET_VERSION=conversation-v2-main-v2`，加载 24 条冻结用例。语义回归可设置为 `conversation-v2-semantic-main-v1`，加载 10 条用例。V2 JSONL 缺失或结构非法时直接失败，不回退 MySQL。运行仍需已登录的本地前端会话。

`expectedV2Outcomes`、`expectedTurnStates` 和 `expectedRelations` 的失败均影响 Case Pass；未配置状态/关系断言时 `memoryMatched=null`，未配置 V2 结果断言时 `v2OutcomeMatched=null`，不计作该项验证通过。`hardConstraintsVerified` 仅在当前轮 `verificationStatus=VERIFIED_PASS` 时为 true。逐轮业务/模型异常记录为 `EXECUTION_ERROR`，评测读取或断言解析异常记录为 `EVALUATION_INFRASTRUCTURE_ERROR`；失败诊断还保留 `assertionFailures`。旧 route、context rewrite 和 legacy tool 断言仅作诊断，不再作为 V2 用例的业务成功门槛。

`/ai/retrieval/indexes/rebuild` 当前为本地调试便利开放；部署前应增加认证和管理员权限校验。

最近一次本地 `structured-profile-evidence-vector-v3` 主评测基线：15 条用例全部完成，Recall@K `0.8333`、MRR `0.5833`、证据覆盖率 `1.0`、硬约束违规数 `0`、模型调用成功率 `100%`、P95 总延迟约 `5.8s`。这些值会随本地数据、模型和检索参数变化，应以运行记录而非 README 数字作为最终结论。

### V2 自然语言多轮受控试点

业务规范 v1.1 已冻结。受控试点覆盖 S04、S08、S09、S13、C06、C15，运行：

```powershell
mvn -q "-Dtest=V2PilotEvaluationTest,V2ControlledPilotTest,AiConversationEvaluationObservationTest" test
```

生成 `target/eval-pilot/plan-v1.json` 与 `controlled-v1.json`，包含冻结计划、完整响应、全任务/历史批次、实际执行边界证据和逐项判定。该测试使用语义模型与外部事实/存储替身，真实 V2 链路创建推荐批次；不能作为真实模型端到端通过率。未执行的业务检查为未验证，前提冲突不算就绪，其他十个场景未运行。准备方式、统计口径和已复现限制见 [试点建设与验证报告](doc/eval/V2自然语言多轮评测-试点建设与验证-v1.md)。

## 建议的开发顺序

1. 先在前端测试 15 到 20 个真实对话，重点覆盖位置补充、指代追问、优惠券、评价、退出和重新进入决策。
2. 固化上述问题为评测用例，分别建立主评测集和保留集，先跑出当前混合检索基线。
3. 分析失败用例：候选召回不足、候选排序不对、证据不足、还是对话状态错误。
4. 只有在“硬筛选候选正确但排序仍明显错误”时，再接入 rerank 模型，并用同一保留集比较 Recall@K、MRR、证据覆盖率、延迟和成本。

## 验证

```powershell
mvn -q test
```

AI 决策链路的本地 JMeter 基线计划和执行方式见 [jmeter/README.md](jmeter/README.md)。默认只运行 4 次真实模型请求；提高并发前需要先确认供应商配额与成本边界。

架构与关键决策的演进记录见 [AI消费决策Agent开发记录.md](doc/AI消费决策Agent开发记录.md)。

## 收尾验收

每次修改 Agent 主链路后，先执行 `mvn -q test`，再以已登录的前端会话运行多轮轨迹评测。评测接口受业务登录保护，避免匿名请求污染运行记录：在浏览器已登录状态下执行：

```javascript
fetch('/api/ai/evaluations/conversation-runs', {
  method: 'POST',
  credentials: 'include'
}).then(async response => console.log(response.status, await response.json()));
```

人工验收至少覆盖：设备定位找店、命名目的地确认、条件细化重搜、无结果松弛、候选序号/店名追问、评价与优惠券并行查询、退出后重新发起推荐，以及 SSE 的 `status -> text_delta -> ui_component -> suggested_chips -> complete` 事件顺序。每次真实评测记录运行 ID、模型版本、检索策略版本和 JMeter 报告路径，才可用于跨版本对比。

## 真实语义模型受控试点（显式 opt-in）

在 `.v2-eval-pilot` Worktree 中使用既有本地模型配置；唯一 runId 防止覆盖已启动的实验：

```powershell
mvn -q "-Dtest=V2RealModelPilotTest" "-Dv2.realModel.enabled=true" "-Dv2.pilot.runId=首次实验的唯一标识" "-Dspring.config.additional-location=file:../src/main/resources/application-dev.yml" test
```

runId 使用字母、数字、下划线或连字符。结果位于 `target/eval-pilot/<runId>/`。只向模型传 Production 解释器实际构造的输入，不补历史或验收答案；模型、商户事实、存储的受控边界分别记录。该模式不代表真实检索端到端通过率，默认 Maven 回归不会调用模型。
