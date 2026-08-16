# 消费决策 Agent

面向本地餐饮消费场景的多轮决策系统。用户可以通过自然语言提出推荐、追问商户评价、优惠券、营业时间和备选方案等问题；系统结合对话状态、结构化事实、评价证据和向量检索生成可追溯的建议。

## 核心能力

- 多轮对话：支持普通闲聊、进入消费决策、在决策中补充位置/预算、自然退出后再次进入。
- 位置优先：每次餐饮推荐默认请求当前位置；用户提供唯一可解析的城市/区域后按该地点检索，像“鼓楼”这类歧义地名必须补充城市，不能猜测或退回全城推荐。
- 工具编排：围绕商户详情、评价证据、优惠券、备选商户等事实工具回答追问，并保存工具调用审计。
- 状态与记忆：`ai_chat_message` 持久化聊天记录，`ai_chat_session` 持久化位置、当前商户和已展示候选等会话槽位；Redis 作为低延迟缓存而不是唯一记忆来源。
- 混合检索：MySQL 负责菜系、预算、距离、营业时间等硬约束；Milvus 只在硬筛选候选白名单内进行语义召回和重排，不会突破地理或品类约束。
- 可解释输出：响应包含决策状态、候选、事实证据、处理 trace 和延迟指标；日志记录模型、工具、状态机和语义召回摘要。
- 离线评测：内置评测集、保留集、Recall@K、MRR、证据覆盖率、约束违例和模型调用指标，支持运行间比较。

## 技术栈

- JDK 21、Spring Boot 3.4、MyBatis-Plus、MySQL、Redis
- Spring AI 1.0：OpenAI-compatible EmbeddingModel 与 Milvus VectorStore
- Milvus：商户画像和评价证据的向量存储
- 兼容 OpenAI 协议的聊天模型与 Embedding 服务；开发环境已验证百炼 `text-embedding-v4`

> 当前并非所有模型调用都由 Spring AI 承担。Spring AI 已用于 embedding 和 Milvus 向量检索；现有聊天路由、Function Calling、工具循环和事实回答仍由项目内 `OpenAiCompatibleClient` 完成。这是有意保留的边界，后续可以在不改动业务状态机的前提下逐步迁移聊天客户端。

## 检索架构

```text
用户问题
  -> 约束提取 / 会话槽位恢复
  -> 位置授权或地点消歧（推荐默认必经）
  -> MySQL 硬过滤（位置、半径、预算、菜系、营业时间）
  -> Milvus 语义召回（仅对硬过滤白名单聚合评分）
  -> 业务重排与证据组装
  -> 可解释回答、trace、工具审计与会话记忆
```

## 本地启动

### 1. 前置条件

- JDK 21
- Maven 3.9+
- MySQL 8、Redis
- Docker Desktop（启用 Milvus 时）
- 一个聊天模型 API Key，以及一个 OpenAI-compatible Embedding API Key

### 2. 配置本地环境

仓库仅提交 [application.yaml](src/main/resources/application.yaml) 的占位符，不提交密钥。创建本地文件 `src/main/resources/application-dev.yml`，或在运行环境设置等价环境变量。

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

基础业务表和 AI 表的 SQL 位于 `src/main/resources/db/migration/`。本项目不自动执行这些脚本；在新数据库中按数字版本顺序执行 `V1` 到 `V20`。

- `V16__agent_business_demo_data.sql`：补充杭州演示餐饮数据、画像、评价证据、券和探店数据。
- `V17_fuzhou_data.sql`：补充福州/闽侯/上街大学城演示数据。

所有 `SEED_DEMO` 评价均为本地演示数据，不能表述为真实线上评价。

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
| 创建一次决策 | `POST /ai/decisions` |
| 决策中的选项/补充 | `POST /ai/decisions/{sessionId}/messages` |
| 重建向量索引 | `POST /ai/retrieval/indexes/rebuild` |
| 运行主评测集 | `POST /ai/evaluations/runs` |
| 运行保留集 | `POST /ai/evaluations/runs/holdout` |
| 对比两次评测 | `GET /ai/evaluations/runs/{runId}/compare/{baselineRunId}` |

`/ai/retrieval/indexes/rebuild` 当前为本地调试便利开放；部署前应增加认证和管理员权限校验。

## 建议的开发顺序

1. 先在前端测试 15 到 20 个真实对话，重点覆盖位置补充、指代追问、优惠券、评价、退出和重新进入决策。
2. 固化上述问题为评测用例，分别建立主评测集和保留集，先跑出当前混合检索基线。
3. 分析失败用例：候选召回不足、候选排序不对、证据不足、还是对话状态错误。
4. 只有在“硬筛选候选正确但排序仍明显错误”时，再接入 rerank 模型，并用同一保留集比较 Recall@K、MRR、证据覆盖率、延迟和成本。

## 验证

```powershell
mvn -q test
```

架构与关键决策的演进记录见 [AI消费决策Agent开发记录.md](doc/AI消费决策Agent开发记录.md)。
