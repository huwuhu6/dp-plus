# 混合检索与 Rerank——简历驱动追问链

## 0. 如果简历要写这一点，怎么写

建议简历 bullet：

> 构建商户混合检索链路，先按行政范围等结构化条件缩小候选集，再在合法候选内使用 Milvus 对商户 Profile / Review 做语义召回，并结合距离、星级、预算偏离等业务信号进行确定性 Rerank；通过 Recall@K、MRR 等指标评估召回与排序效果。

这句话主动暴露的关键词只有：结构化过滤、Milvus、Profile / Review、Rerank、Recall@K / MRR。不要在同一条 bullet 里再塞 Vector Sync、Embedding 参数、索引治理、Evidence Tool 等细节。

---

## 1. 面试官第一问：为什么 MySQL 和 Milvus 两套都要？

### 面试官为什么会问

因为如果 MySQL 能查，向量库可能显得多余；如果向量库能搜，MySQL 又可能显得重复。你要先说明两类问题本质不同。

### 核心回答

结构化约束解决“合法不合法”，语义检索解决“像不像用户想要的”。预算、行政范围、营业等是确定业务约束，不能靠 vector similarity 决定；“适合聊天”“氛围轻松”“口味清淡”这类开放表达则更适合语义表示。

### 40 秒回答

> 我没有把推荐问题全部交给向量检索。结构化约束和语义相关性解决的是两类问题：预算、行政范围、营业等条件属于业务合法性，先限制 candidate universe；在满足硬条件的候选里，再用 Milvus 对 Profile / Review 做语义评分，最后结合距离、星级、预算偏离做 deterministic rerank。这样 semantic score 再高，也不能把违反硬条件的商户重新带回来。

---

## 2. 为什么不能纯向量？

### 面试官可能直接举例

> 用户说人均100，向量库不是也能搜到“便宜”“性价比高”的店吗？

核心回应：自然语言“便宜”和业务规则“avgPrice <= 100”不是一回事。前者是语义偏好，后者是可验证约束。把确定约束交给相似度会产生不可解释的违例。

### 反向追问

> 那为什么不能全 MySQL？

因为用户表达并不总是结构化字段，例如“适合聊天”“环境安静”“重口吗”“氛围好一点”。数据库精确条件可以缩小范围，但无法覆盖开放语义；这时 Profile / Review embedding 才有价值。

---

## 3. 真实检索链路到底怎么走？

这里必须按当前代码讲，不能说成“所有 hard filter 都 SQL 下推”。

当前口径：

```text
province / city / district / excludeShopIds
→ MySQL SQL 粗筛 Shop

加载 AiShopProfile
→ Java matchesHardConstraints()
→ 预算、菜系等继续做 hard constraint filtering

得到 hardMatched candidate universe
→ Milvus 只在合法候选内做 Profile / Review semantic scoring

Java deterministic rerank
→ semantic score + distance + rating + budget deviation ...

TopN
→ evidence 组装 / generation
```

### 面试官反驳

> 预算、菜系为什么不也 SQL 下推？

不要硬装。当前实现仍有部分 hard filter 在 Java 内存层完成，这是工程债和扩展性限制。正确回答应该是：语义上这些条件属于 hard filter，但当前物理执行位置不是全部 SQL；数据规模扩大后应继续做 query pushdown、索引与分页治理。

---

## 4. Milvus 里面到底存什么？

### 面试官会问得很具体

> 一家店一个向量吗？评论那么多怎么放？

当前项目主要有两类语义文档：

```text
Shop Profile
→ 表达商户整体画像、菜系、特色、场景等

Review Document
→ 表达单条/局部用户评价证据
```

两者职责不同。Profile 更适合“这家店整体是否符合用户偏好”；Review 更适合“重口吗、服务怎么样、环境如何”这类 evidence-backed subjective fact。

### 为什么不全拼成一个大向量？

因为把所有评论压成一个向量会丢失局部证据和可解释性；而只用 Review 又容易缺失稳定商户画像。Profile 和 Review 分开能让整体匹配与证据检索各自发挥作用。

---

## 5. 为什么有向量分数还要 Rerank？

### 面试官自然追问

> Milvus 已经给 similarity score 了，按分数排序不就行？

向量分数只表达语义接近程度，不等价于最终消费决策目标。用户还关心距离、预算、人均偏离、星级等业务信号。

因此 ANN retrieval 负责把“可能相关”的候选召回来，Rerank 再按最终业务目标排序。

### 项目里的口径

当前主要是 deterministic business rerank，而不是 Cross-Encoder / LLM reranker。这样做的优点是成本低、可解释、可测试；缺点是业务权重需要实验标定，表达能力不如学习型 reranker。

---

## 6. 你的 0.35、TopK=80、semanticWeight=18 怎么来的？

这是高风险追问，不能装成“最优参数”。

### 正确回答

当前做过检索实验和 ablation，能够说明 semantic scoring 方向有效，但 `semanticMinScore=0.35`、TopK=80、`semanticWeight=18` 仍然是单点配置，不能说已经通过系统化搜索得到全局最优。

更完整的调参应该固定数据集，联合观察：

```text
Recall@K
MRR
Evidence hit / coverage
Hard Constraint Violation
Latency P50 / P95
候选规模
```

然后做阈值、TopK、权重的 grid / staged experiment。

### 面试官可能直接说

> 那你这些参数就是拍脑袋？

回答：不是完全拍脑袋，已有实验验证“启用语义评分相对 baseline 有收益”；但具体单点参数仍缺少完整搜索，因此我不会包装成最优值。这是当前明确的实验债。

---

## 7. Recall@K 和 MRR 分别说明什么？

### Recall@K

关心 Ground Truth relevant shops 有多少进入 TopK：

```text
Recall@K = |Relevant ∩ TopK| / |Relevant|
```

Retriever 没把正确候选召回来，后面的 Rerank 再强也救不回来。

### MRR

单个 Query 先看“第一个 relevant result 排第几”，取 reciprocal rank；再跨 Query 求平均。

```text
结果：[错, 错, 对, 对]
RR = 1/3
```

一个 Query 即使有多个正确结果，RR 也只看第一个 relevant 的位置。

### 区别

Recall 更看“有没有把相关结果尽量召回来”；MRR 更看“第一个正确结果是否足够靠前”。两者不能互相替代。

---

## 8. 如果效果不好，你会怎么定位？

不要第一反应“调 TopK”。先做 error decomposition：

```text
正确商户在 hard filter 前就没进候选
→ 查结构化条件 / metadata / location

候选合法，但 vector recall 没命中
→ 查 query representation / embedding / threshold / TopK / index

召回有了，但最终顺序差
→ 查 rerank signals / weights

商户对了，但“重口/环境”等回答仍错
→ 查 review evidence / context assembly / generation
```

这能证明你知道 Retrieval、Rerank、Generation 是不同责任层。

---

## 9. 商户和评论变化以后，向量怎么增量更新？

这条不建议主动写简历，但 Milvus 被追深后很容易问。

### 面试官可能问

> MySQL 里的商户改了，Milvus 怎么保证同步？事务里直接写吗？

不要把外部向量库网络 IO 绑在本地业务事务里，因为会出现：

```text
MySQL commit 成功
Milvus 失败
```

两边没有一个本地 ACID transaction。

当前使用 durable Vector Sync Task：业务事务只持久化 documentId、operation、targetRevision 等 desired state；后台 Worker claim lease 后执行 Milvus UPSERT / DELETE，失败退避重试，lease 过期可恢复，同一 documentId 用 revision 防止旧任务覆盖新状态。

### Trade-off

这是最终一致性，不是强一致；优点是解耦外部网络 IO，失败可恢复；代价是短时间内 MySQL 和 Milvus 可能存在版本差，需要状态/监控和补偿策略。

---

## 10. 为什么不直接上 Cross-Encoder / LLM Reranker？

面试官可能质疑 deterministic rerank 表达能力。

回答重点：当前候选规模、业务信号都比较结构化，距离、预算、星级属于可解释且稳定的排序因子，确定性重排成本低、延迟稳定、容易做 contract test。Cross-Encoder / LLM reranker 适合更复杂的语义排序，但会增加调用成本、延迟和评测复杂度。是否引入应由离线 ranking metric 的增益证明，而不是为了“技术更高级”。

---

## 11. 这条线最后怎么收口

> 我把检索分成三个职责：结构化约束先限制合法候选，Milvus 在合法候选内解决开放语义相关性，最后再用确定性 Rerank 把语义分和距离、星级、预算等业务目标结合。当前已有 Recall@K、MRR 和检索实验验证语义评分有收益，但阈值、TopK 和权重还不能说是全局最优；另外部分 hard filter 仍在 Java 层完成，这是数据规模扩大后需要继续下推和治理的工程点。

---

## 12. 当前不能吹什么

- 不能说所有 hard constraint 都已 SQL 下推；
- 不能说 0.35 / TopK=80 / weight=18 是系统化调参后的最优值；
- 不能说项目是典型 PDF Chunk RAG；它更接近商户 Profile / Review 的结构化约束 + 语义检索 + grounded generation；
- 不能说当前已经实现 nDCG、Cross-Encoder rerank、完整 LLM Judge Faithfulness 等行业常见能力；
- 语义召回仍存在一部分未完全归因的失败，需要继续区分 embedding 能力、TopK 截断和 candidate universe 限制。

---

## 13. 开发记录里真正能拿出来的检索实验结论

开发记录里做过 Semantic Scoring Ablation，不只是“跑通 Milvus”。其中一个比较重要的结论是：启用 semantic scoring 的方向有收益，但仍有 7/112 个 Ground Truth 属于 `SEMANTIC_RETRIEVAL_ERROR`，约 6%，而且在 Base / Semantic / Semantic 2x 三组里这 7 个都没有消失。

这意味着失败不能简单归因成“权重太小”。更可能需要继续区分：

```text
Embedding 本身表达能力不足
TopK 截断
Milvus candidate restriction
前置 hard candidate universe 已经丢失正确商户
```

当前已有实验口径里可引用 Recall@3 约 0.76、Hard Constraint Violation 为 0；这能说明系统没有为了提高语义召回放弃业务合法性，但也不能说 retrieval 已经接近完美。

### 面试官问：那为什么不继续把这 6% 修掉？

回答重点：先做 error decomposition。7 个 GT 在加倍 semantic weight 后仍不变，说明继续调权重很可能没有收益；应该先确认正确向量是否进入 TopK、候选是否在前置 hard filter 阶段就被排除，再决定改 embedding、TopK 还是 candidate generation。

---

## 14. Vector Sync 被追深时，要能说到“旧 Worker 不能覆盖新状态”

不要只背“异步最终一致”。当前代码里 `VectorSyncTaskService` 有明确的 lease / status；集成测试还覆盖了一个关键竞争场景：旧 worker 持有旧 lease 和 revision 时，`markSynced` 不能错误把更新后的任务标成已同步，而是通过 requeue 保留 newer desired state。

可以把它讲成：

```text
revision=10 的 UPSERT 被 worker A claim
↓
期间业务又产生更新 / DELETE
↓
任务 desired state 已变化
↓
A 完成旧工作时不能把新状态覆盖成 SYNCED
↓
按 operation + targetRevision + lease token 做条件更新
```

所以这里真正解决的不是“定时任务重试”，而是异步外部索引同步中的 stale worker 问题。

### 面试官可能反驳

> 那为什么不用 MQ？

当前选择 durable task table 的优势是 desired state、revision、lease、retry 都可直接落库和审计，项目规模下实现成本更低；MQ 更适合更高吞吐、跨服务事件分发，但仍需要消费幂等、失败重试和最终状态收敛，不能自动消除一致性问题。

---

## 15. 这条线建议你真正背熟的不是参数，而是四个判断

```text
1. 哪些条件必须 hard filter，为什么；
2. 为什么 vector score 不能直接作为最终排序；
3. 检索失败怎么分层归因；
4. 数据更新后怎么保证 MySQL 与 Milvus 最终收敛。
```

0.35、80、18 只是追问时的实现细节。真正能让你扛住面试的是你能解释这些参数为什么不能替代业务 Contract，以及实验为什么只能证明“方向有效”而不能证明“全局最优”。
