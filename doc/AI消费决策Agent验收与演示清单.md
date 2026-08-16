# AI 消费决策 Agent 验收与演示清单

这份清单用于项目收尾、答辩演示和重新验证。它不要求读代码，只需要已经登录并在请求头中携带当前登录态的 `authorization`。

## 0. 启动前检查

1. MySQL 中的 `hmdp` 数据库已经执行 V1 到 V14 迁移。
2. Redis、MySQL 和服务端已启动，服务地址为 `http://127.0.0.1:8081`。
3. 启动服务的同一个终端中配置了 `DEEPSEEK_API_KEY`。没有 Key 也能演示规则降级，响应会给出“未配置模型服务”的 `degradedReason`，但最终评测的模型成功率没有参考价值。
4. Apifox 创建环境变量 `baseUrl=http://127.0.0.1:8081`，并在请求头放入登录接口得到的 `authorization`。不要把 token 写进项目文档或提交到 Git。

## 1. 推荐主流程

### 1.1 默认定位澄清

`POST {{baseUrl}}/ai/decisions`

```json
{
  "query": "帮我找安静的日料"
}
```

预期：

- `status` 为 `CLARIFYING`。
- `question` 请求提供当前定位；`options` 包含 `PROVIDE_LOCATION`、`DECLINE_LOCATION` 和 `END_DECISION`。
- 当前未接入地理编码工具时，输入“鼓楼”“福州鼓楼”等地点名称也不会被硬编码为坐标。

### 1.2 缺定位后拒绝定位

先发：

`POST {{baseUrl}}/ai/decisions`

```json
{
  "query": "找附近安静的日料"
}
```

预期先得到 `CLARIFYING`，并记录返回的 `sessionId`。随后发：

`POST {{baseUrl}}/ai/decisions/{sessionId}/messages`

```json
{
  "selectedOptionId": "DECLINE_LOCATION"
}
```

预期：

- 最终为 `COMPLETED`，按全城而不是“附近”检索。
- `constraints.nearby=false`、`radiusKm=-1`。
- `hardConstraints` 和 `missingInformation` 不应再包含位置/附近信息。
- `softPreferences` 有“用户未提供位置，按全城搜索”。

### 1.3 无候选后的人工放宽

`POST {{baseUrl}}/ai/decisions`

```json
{
  "query": "找附近100米的日料",
  "latitude": 30.2741,
  "longitude": 120.1551
}
```

若返回 `WAITING_RELAXATION`，从 `options` 中选择当前存在的 `EXPAND_RADIUS`，再调用：

`POST {{baseUrl}}/ai/decisions/{sessionId}/messages`

```json
{
  "selectedOptionId": "EXPAND_RADIUS"
}
```

预期：系统只扩大距离，不自行改变预算、菜系或安静等其他条件；响应指标中的 `relaxationCount=1`。

### 1.4 用户中途换话题

在 `CLARIFYING` 或 `WAITING_RELAXATION` 会话上调用：

`POST {{baseUrl}}/ai/decisions/{sessionId}/messages`

```json
{
  "message": "算了，我想问天气"
}
```

预期：状态为 `CANCELLED`，不会把无关文本当成放宽条件，也不会继续查询商户。真正的新消费需求应重新调用 `POST /ai/decisions`。

## 2. 最终评测基线

这一步是收尾时唯一需要真实模型环境的验证。两次调用请串行进行，避免并发混淆日志和模型耗时。

### 2.1 开发集

`POST {{baseUrl}}/ai/evaluations/runs`

记录 `run.id`。最低验收条件：

- `status=COMPLETED`
- `statusMatchedCount=caseCount`
- `constraintMatchedCount=caseCount`
- `followUpStatusMatchedCount=followUpEvaluatedCount`
- `hardConstraintViolationCount=0`
- `factualConsistentCount=caseCount`

模型服务有偶发失败是允许观测的，不要为了追求满成功率重复运行到“好看”为止；需要同时记录 `modelFailureCount`、平均耗时和 P95。

### 2.2 独立保留集

`POST {{baseUrl}}/ai/evaluations/runs/holdout`

记录 `run.id`。最低验收条件同上，但保留集只有 4 条，作用是检查不同表达的基本泛化，不能被表述为线上效果证明。

### 2.3 同版本比较（可选）

仅当两个运行使用相同模型、相同数据集版本和相同用例数量时调用：

`GET {{baseUrl}}/ai/evaluations/runs/{currentRunId}/compare/{baselineRunId}`

重点一起看质量与延迟：状态/约束命中率、模型失败率、平均耗时、P95、Recall@K、MRR 和证据覆盖率。平均耗时变快而 P95 变慢是可能的，不应只挑单个指标下结论。

## 3. 演示结论边界

可以说：系统具有可恢复多轮状态机、确定性事实约束、证据推荐、模型失败降级，以及开发集和独立保留集评测。

不能说：地点名距离是真实地图结果、`SEED_DEMO` 是线上真实评论、4 条保留集等同线上泛化、或者当前已经实现个性化学习和向量检索。
