# AI 消费决策 Agent——Working Memory 主线面试资料

> 当前资料只围绕：Working Memory、Task、RecommendationBatch、Reference、状态持久化/OCC、同轮状态一致性、TurnPlan，以及和它们直接相关的评测。
>
> RAG、SSE、Tool Planner 暂不单独展开。
>
> 当前项目定位也要说准确：这是一个学习/求职型 Agent 项目，不应包装成真实大规模线上生产系统。它目前真正有价值的地方，是通过 Evaluation 持续暴露多轮状态问题，并逐步形成一套确定性的对话业务状态管理方案。

## 一、先把项目主线讲清楚

**Q1：介绍一下你这个项目？**

我做的是一个点评业务场景下的消费决策 Agent。用户可以连续表达类似“福州吃火锅，人均 100”“换成杭州日料”“还是最开始那套”“第一家太贵了，第二家有插座吗”这样的需求，系统需要在多轮对话中持续维护地点、预算、菜系、候选商户、当前关注商户以及历史推荐上下文。

但需要先说明一个重要的时间线：

> 这个项目最早并没有 Working Memory。

最初的实现更接近一个“聊天历史 + 当前请求上下文 + 若干临时字段”的 Agent。系统能够完成单轮推荐，也能处理一部分简单的多轮追问，但没有一个明确、持久化、可版本化的业务状态模型。很多状态实际上散落在聊天记录、Pipeline Context、候选列表和会话字段中。

整个聊天入口目前是一条显式 Pipeline：

```text
Bootstrap
→ Context Rewrite
→ Intent Routing
→ Criteria Reduction
→ Policy Guard
→ Execution
```

其中模型主要处理自然语言语义，例如约束提取、部分 Routing 和 ReferenceIntent 提取；真正的业务状态合并、Task 切换、候选失效、版本并发控制等由 Java 确定性逻辑负责。

我后面主要投入的其实不是“怎么让模型更聪明”，而是解决一个更工程化的问题：

> **多轮 Agent 怎么保证状态不串、历史方案能恢复、旧工具结果不能覆盖新状态，而且这些行为还能被自动评测验证。**

当前 `ChatProcessingContext` 明确被定义为单 Turn 的临时状态，而可持久化的业务事实放在 `ConversationWorkingMemory` 中。

但这个结论不是一开始就有的，而是从早期多个状态问题逐步演化出来的。

面试时不要把这个项目简单介绍成：

> “我用了 Spring AI、Milvus、Function Calling 做了一个餐饮 Agent。”

这样听起来很像 Toy。

应该把重点落在：

> “我围绕多轮消费决策中的状态一致性问题，从最初没有 Working Memory 的实现开始，通过 Evaluation 暴露候选池、历史引用、条件修改和多方案切换等问题，逐步演化出 Working Memory、Task Scope、RecommendationBatch 和版本化持久化机制。”

------

**Q2：最早没有 Working Memory 时，系统是什么样的？为什么后来必须引入它？**

最早的系统并不是完全没有状态，而是没有一个明确的 Working Memory 抽象。

当时的状态大致分散在几处：

```text
Chat History
当前请求的 ChatProcessingContext
当前候选商户列表
当前 focused shop
部分 Conversation 字段
```

可以粗略理解为：

```text
Conversation
├─ chat history
├─ current criteria
├─ candidatePool
├─ shownPool
└─ focusedShop
```

这些字段能够支撑简单流程：

```text
福州火锅，人均100
→
预算改成80
```

但一旦进入多轮、多批次、多方案场景，问题就开始暴露。

最早遇到的并不是 Task，而是候选集合语义混乱。

例如：

```text
第一轮：
推荐 A B C

用户：
换一批

第二轮：
推荐 D E F
```

当时如果只维护一个 `candidatePool`，它很容易被第二批覆盖：

```text
candidatePool = [D,E,F]
```

但系统同时又需要知道：

```text
用户已经看过 A B C D E F
```

否则下一次“换一批”可能重新推荐 A、B、C。

于是我们开始把：

```text
当前可用于继续决策的候选
```

和：

```text
用户历史上已经看过的候选
```

拆开。

也就是：

```text
candidatePool
vs
shownPool
```

这两个概念解决的是不同问题：

```text
candidatePool
= 当前这次推荐或当前条件下仍然有效的候选集合

shownPool
= 用户在当前决策上下文中已经看过的商户集合
```

例如：

```text
第一批：
candidatePool = [A,B,C]
shownPool = [A,B,C]

换一批之后：
candidatePool = [D,E,F]
shownPool = [A,B,C,D,E,F]
```

这样才能同时支持：

```text
换一批不要重复
```

和：

```text
之前第一家怎么样
```

但这只是第一步。

后来又发现，`candidatePool` 和 `shownPool` 分开以后，仍然无法解决历史推荐批次的问题。

如果用户说：

```text
最开始第二家怎么样？
```

仅仅知道：

```text
shownPool = [A,B,C,D,E,F]
```

还不够。

因为 `shownPool` 只知道“看过哪些”，不知道：

```text
第一批的第二家是谁
第二批的第二家是谁
```

所以又进一步引入了：

```text
RecommendationBatch
```

也就是：

```text
RecommendationBatch 1 = [A,B,C]
RecommendationBatch 2 = [D,E,F]
```

这时才可以稳定解析：

```text
最开始第二家
→ RecommendationBatch 1
→ index 2
→ B
```

这个演进过程很重要：

```text
没有 Working Memory
→ candidatePool / shownPool 分离
→ RecommendationBatch
→ Task Scope
→ ConversationWorkingMemory
```

所以 Working Memory 不是一开始凭空设计出来的，而是为了收拢之前散落在多个对象中的业务状态。

------

**Q3：你这个项目里最难的点是什么？遇到过最大的坑是什么？**

我认为最难的是 **多轮 Working Memory 的状态隔离和一致性**。

这个问题不是一开始就设计出来的，而是几轮 Bad Case 一步步逼出来的。

第一阶段，系统甚至没有 Working Memory，主要依赖聊天历史和当前上下文。

第二阶段，开始把候选池和已展示池拆开：

```text
candidatePool
shownPool
```

解决了“换一批重复推荐”的问题，但仍然无法稳定支持：

```text
历史批次引用
多方案并存
条件修改后的候选失效
```

第三阶段，引入了 RecommendationBatch：

```text
Task
└─ recommendationBatches[]
```

解决了“第一批第二家”这类历史引用问题。

但当时仍然只有一份全局活动条件，类似：

```text
WorkingMemory
├─ activeCriteria
├─ searchLocation
├─ candidatePool
├─ focusedShop
└─ dialogPhase
```

这种 Flat Working Memory 处理简单 refinement 没问题：

```text
福州火锅，人均100
→
预算改成80
```

直接在原条件上 merge 就可以。

真正的问题出现在用户同时维护多个方案时：

```text
Turn1：
福州吃火锅，人均100

Turn2：
杭州西湖吃日料，人均300

Turn3：
还是最开始那套
```

Flat Working Memory 只有一份活动条件。Turn2 执行之后，福州火锅那套状态实际上已经被杭州日料覆盖了。

Turn3 再回来，只靠：

```text
当前 Flat State + 本轮 Delta
```

很容易得到这种错误状态：

```text
福州
火锅
预算300
甚至残留西湖
```

也就是我们后来总结的 **Ghost Inheritance，幽灵继承**。

所以第一步是引入 Task Scope。

现在 `ConversationWorkingMemory` 里面不再直接保存一份全局 `activeCriteria`，而是：

```java
private String activeTaskId;
private List<DecisionTaskState> tasks;
```

而每个 `DecisionTaskState` 自己维护：

```java
private DecisionConstraints criteria;
private Map<String, ConstraintSource> constraintSources;
private ConversationLocationSlot searchLocation;
private List<RecommendationBatch> recommendationBatches;
```

于是状态变成：

```text
Conversation
├─ Task A
│  ├─ 福州
│  ├─ 火锅
│  └─ 100元
│
└─ Task B
   ├─ 杭州西湖
   ├─ 日料
   └─ 300元
```

用户说“回到最开始那套”时，不是拿当前 B 重新拼一个 A，而是重新激活原来的 Task A。

但这里又踩了一个更隐蔽的坑。

Task V2 第一版写完之后，Task transition 的单元测试是通过的，但是完整 E2E Evaluation 仍然只有：

```text
Scope 2/4
```

逐轮查看发现：

```text
taskCount 始终 = 1
activeTaskId 始终不变
```

最后发现不是 Task 判断规则错了，而是**一个请求内部用了两份不同时间点的 Working Memory**：

```text
transitionTask()
    ↓
修改了 Pipeline 内存中的 WorkingMemory

然后

reduceCriteria()
    ↓
又从数据库重新 load WorkingMemory
    ↓
拿到 transition 前的旧版本
```

所以 Task 明明创建了，到后面又被旧快照覆盖掉。

最终我们确定了一个非常重要的不变量：

```text
一次 Pipeline Processing 内：

bootstrap snapshot
    ↓
task transition
    ↓
criteria merge
    ↓
reduce
    ↓
persist

必须使用同一份 Working Memory Snapshot。
```

现在 `prepareDecision()` 直接使用：

```java
context.getWorkingMemory()
```

做：

```text
transitionTask
→ merge
→ reduceCriteria
```

不会在中间重新加载旧状态。

对应代码里甚至专门留下了注释：

```java
/** Persists a reduction against the pipeline snapshot
 * so a task transition is not lost between nodes.
 */
public void reduceCriteria(
        AiChatSession state,
        ConversationWorkingMemory memory,
        CriteriaMergeResult reduction)
```

最终 Scope 演进是：

```text
Flat Working Memory：2/4

Task V2 初版：2/4

修复同轮 Snapshot 一致性后：4/4
```

开发记录里保留了这个完整过程。

我认为这就是整个项目目前最值得讲的故事。

因为它不是：

> “我设计了一个 Task 类。”

而是：

> “我先从没有 Working Memory 的实现开始，先后遇到 candidatePool/shownPool 混淆、历史批次丢失、Flat State 覆盖和同轮双状态视图等真实问题，再逐步引入 RecommendationBatch、Task Scope 和 Request-scoped Snapshot，最后通过 Evaluation 验证状态模型确实改善了行为。”

------

**Q4：candidatePool 和 shownPool 为什么要分开？这个问题具体解决了什么？**

这是 Working Memory 演进中比较早、也比较关键的一次拆分。

最初系统很容易把“当前候选”与“已经展示过的候选”混成一个列表。

但这两个集合的生命周期和语义不同。

```text
candidatePool
= 当前条件下可以继续使用的候选

shownPool
= 用户已经看过的候选
```

例如：

```text
第一轮：
A B C
```

此时：

```text
candidatePool = [A,B,C]
shownPool = [A,B,C]
```

用户说：

```text
换一批
```

系统需要排除已经展示过的商户，再生成：

```text
D E F
```

此时：

```text
candidatePool = [D,E,F]
shownPool = [A,B,C,D,E,F]
```

如果不分开，通常会出现两类错误。

第一类是重复推荐：

```text
candidatePool = [A,B,C]
```

下一次“换一批”仍然从 A、B、C 中选。

第二类是历史引用丢失：

为了避免重复，系统直接把旧候选删掉：

```text
candidatePool = [D,E,F]
```

但如果没有 `shownPool` 或 RecommendationBatch，系统又无法知道用户之前看过 A、B、C。

所以这次拆分的核心不是“多存一个 List”，而是把两个不同的问题分开：

```text
当前可用性
vs
历史可见性
```

后来 RecommendationBatch 又进一步解决了顺序和批次问题：

```text
shownPool
只能回答：
用户看过哪些商户？

RecommendationBatch
还能回答：
用户在哪一批、以什么顺序看过这些商户？
```

因此现在的关系可以理解为：

```text
RecommendationBatch[]
    ↓ projection
latestCandidatePool

RecommendationBatch[]
    ↓ union
shownShopIds
```

也就是说：

```text
latestCandidatePool
```

是当前候选的投影；

```text
shownShopIds
```

是历史展示集合的投影；

而真正保留完整历史语义的是：

```text
recommendationBatches[]
```

------

**Q5：为什么不能直接把聊天记录当 Memory？为什么还要专门设计 Working Memory？**

这里一定要分清楚：

```text
Chat History ≠ Working Memory
```

聊天记录保存的是：

> 用户和模型“说过什么”。

Working Memory 保存的是：

> 系统现在“相信什么状态是真的”。

最早没有 Working Memory 时，系统实际上经常需要从聊天记录重新推断：

```text
当前地点是什么？
当前预算是多少？
当前正在讨论哪套方案？
当前候选来自哪一批？
用户说“第一家”指的是谁？
```

这会导致每一轮都重新解释历史。

例如用户连续说：

```text
人均100以内
→ 算了，预算不限
→ 还是100吧
```

如果只把全部聊天记录扔给模型，那么下一轮到底预算是多少，需要模型每次重新解释历史。

这会有几个问题：

1. 每轮都重新做语义推理；
2. 历史越长 Token 越多；
3. 模型可能对旧条件和新条件理解不一致；
4. “清除条件”很难表达；
5. 并发情况下没有一个确定的 canonical state；
6. 业务代码无法直接判断当前真实预算、地点和候选池；
7. 历史批次和 ordinal reference 很难稳定恢复；
8. 条件修改后哪些候选失效，无法通过聊天文本直接确定。

所以我的设计是：

```text
Chat History
= 语言上下文

Working Memory
= 当前业务真值
```

项目里甚至真的同时存在两套东西。

`ChatMemoryService` 维护最近聊天消息，目前是 Redis List，最多 8 条消息、30 分钟 TTL，并可以从 Runtime Event 恢复。

而 `ConversationWorkingMemory` 单独维护结构化业务状态。

这个区分和成熟 Agent Framework 的方向其实是一致的。

OpenAI Agents SDK 的 `Session` 主要解决的是**会话历史持久化**：读取旧 conversation items、追加新的 user/assistant items。

LangGraph 则进一步把 thread-level short-term memory 作为 Agent State 管理，并通过 checkpointer 持久化。

所以面试时可以这样回答：

> OpenAI Session 更接近我项目里的 Chat History；而我的 ConversationWorkingMemory 更接近一个业务特化后的 Thread State。它不能被单纯聊天记录替代。

------

**Q6：你现在的 Working Memory 到底存了哪些东西？为什么这么分？**

现在最好按三个 Scope 理解。

```text
Turn Scope
Conversation Scope
Task Scope
```

第一层，**Turn Scope**：

```text
ChatProcessingContext
```

里面有：

```text
originalMessage
effectiveMessage
criteriaDelta
TurnPlan
mergedConstraints
PolicyDecision
Response
...
```

这些都是这一轮 Pipeline 的中间产物，Turn 结束以后不应该被当成长期业务状态。

第二层，**Conversation Scope**：

```text
ConversationWorkingMemory
```

主要有：

```text
deviceLocation
pendingLocationCandidates
activeTaskId
tasks[]
focusedShopId
focusedShopName
dialogPhase
activeDecisionSessionId
lastDecisionSessionId
```

第三层，**Task Scope**：

```text
DecisionTaskState
```

保存：

```text
criteria
constraintSources
searchLocation
recommendationBatches
```

其中 `recommendationBatches` 不是简单的候选缓存，而是历史推荐事实：

```text
第几批
什么时候生成
基于什么 Decision Session
包含哪些候选
候选的展示顺序
```

为什么地点还要拆成：

```text
deviceLocation
vs
Task.searchLocation
```

因为这是两个完全不同的事实。

例如：

```text
我人在福州
但我问：
“帮我看看杭州西湖的日料”
```

设备位置仍然应该是福州。

杭州西湖只是当前 Task 的搜索目标。

如果两者用一个 location：

```text
杭州搜索
→ 覆盖设备位置
→ 下一轮“我附近呢”
→ 系统还以为用户在杭州
```

所以设备位置属于 Conversation，而命名目的地属于 Task。

这不是为了“类设计得漂亮”，而是为了解决两个生命周期不同的状态不能互相覆盖的问题。

------

**Q7：Task 是怎么判断新建、切换还是继续修改的？为什么预算不属于 Task Identity？**

当前 Task transition 是确定性规则，不是让 LLM 直接决定 TaskId。

大致是：

```text
没有 activeTask
→ 创建首个 Task

明确历史引用
→ SWITCH

当前解析出来的 city/area/cuisine
唯一命中历史 Task
→ SWITCH

当前和原方案 city+cuisine 都变化
→ CREATE

否则
→ UPDATE
```

当前 Task 的 `DemandSignature` 主要是：

```text
city
area
cuisine
```

而真正 CREATE 比较保守：

```java
current.hasCityAndCuisine()
&& resolved.hasCityAndCuisine()
&& city changed
&& cuisine changed
```

为什么预算不参与 Task Identity？

例如：

```text
福州火锅100
→
预算改80
```

从产品语义上，这明显还是在修改同一个吃饭方案。

如果 budget 也参与身份：

```text
100元火锅 = Task A
80元火锅 = Task B
120元火锅 = Task C
```

Task 会无限碎片化。

所以：

```text
地点 / 菜系
更接近“我在考虑什么方案”

预算 / 半径 / 偏好
更接近“这个方案有什么约束”
```

这是我们当前的建模选择。

但是这里有一个一定要诚实承认的边界：

> 当前 Task Identity 仍然是一套业务启发式规则，并不是通用的“任务理解算法”。

例如“只换城市但仍吃火锅”当前会偏向 UPDATE，因为项目评测里把这类行为定义成原任务的 Location Refinement。

工业系统里 Task Identity 最终应该由明确的产品语义、结构化 Intent 或 Task Reference 决定，而不能无限添加 Java if/else。

------

**这里目前发现了一个真实架构债。**

当前 `activateHistoricalTask()` 仍然直接写着类似：

```java
if (text.contains("最开始")) ...
else if (text.contains("之前") || text.contains("上一个")) ...
```

也就是说：

```text
自然语言理解
```

还泄漏进了：

```text
ConversationStateService
```

而 Reference 模块我们后来已经演化成：

```text
Natural Language
→ ReferenceIntent
→ deterministic Resolver
```

所以 Task 历史切换这块其实还没有完全达到相同的职责分层。

这属于后续值得专门审计的一点：

```text
Natural Language
→ TaskReferenceIntent
→ Task Resolver
```

而不是继续给 `ConversationStateService` 加：

```java
contains("刚开始")
contains("前一个")
contains("之前那个")
...
```

目前先记录，不直接为了面试而重构。

------

**Q8：为什么有了 Task 以后还需要 RecommendationBatch？candidatePool 不够吗？**

Task 解决的是：

> “我现在在讨论哪套方案？”

RecommendationBatch 解决的是：

> “这套方案历史上给用户展示过哪些结果？”

而在更早的实现里，`candidatePool` 和 `shownPool` 的拆分已经解决了另一个问题：

```text
candidatePool
= 当前候选

shownPool
= 历史展示过的候选
```

但 `shownPool` 仍然不包含批次和顺序信息。

假设：

```text
第一批：
A B C

用户：
换一批

第二批：
D E F

用户：
最开始第二家怎么样？
```

如果 Working Memory 只维护：

```text
candidatePool = [D,E,F]
```

那第一批 A/B/C 已经丢失了。

如果只维护：

```text
shownPool = [A,B,C,D,E,F]
```

又无法知道：

```text
最开始第二家
```

到底是 B 还是 E。

所以现在每个 Task 里面保存：

```text
recommendationBatches[]
```

每个 Batch 记录：

```text
decisionSessionId
candidates[]
```

当前的：

```text
latestCandidatePool
```

本质上只是：

> 最后一个 RecommendationBatch 的 projection。

而：

```text
shownShopIds
```

是所有历史 Batch 的 union。

因此三者关系是：

```text
RecommendationBatch[]
    ├─ latest batch → latestCandidatePool
    └─ all batches → shownShopIds
```

这样既可以做：

```text
“换一批不要重复”
```

又可以做：

```text
“最开始第二家”
```

这两个需求。

------

**Q9：为什么修改条件时要 Candidate Invalidation，但又不能把历史 Batch 真删掉？**

这里要区分：

```text
当前检索结果是否仍有效
```

和：

```text
用户历史上是否看过这些结果
```

例如：

```text
福州火锅 <= 100
→ 推荐 A B C

用户：
预算改成80
```

A/B/C 是基于旧预算得到的。

所以当前 candidate universe 已经失效，不能继续拿它当：

```text
当前可选候选
```

现在 Reducer 会检查：

```text
changesCandidateUniverse(reduction)
```

如果条件变化影响检索空间，会：

```text
invalidateCandidatePool
clear focused shop
```

但是历史 Batch 仍然有业务意义：

> 用户之前确实看到过 A/B/C。

所以 RecommendationBatch 是历史语义；latest candidate pool 是当前语义。

这个区分对于：

```text
“刚才第一家”
“换一批”
“之前第二家”
```

非常重要。

更准确地说：

```text
Candidate Invalidation
= 让旧结果退出当前决策空间

不是
= 从历史中删除旧结果
```

这也是早期 `candidatePool` / `shownPool` 拆分最终演化成 RecommendationBatch 的原因。

------

**Q10：Constraint 为什么还要保存 Source？**

目前 Task 里不只保存：

```text
budget = 94
```

还会保存这个条件是怎么来的：

```java
USER_EXPLICIT
SYSTEM_DEFAULT
DERIVED
```

比如：

```text
用户：
预算100
```

这是：

```text
USER_EXPLICIT
```

而：

```text
用户：
第一家太贵了
```

如果第一家人均 110，我们根据 relative critique 推导出新的预算上界，这个预算就应该是：

```text
DERIVED
```

当前 State Reducer 也确实会在 relative budget / relative distance 时把来源标记为 `DERIVED`。

为什么要做 provenance？

因为：

```text
“用户明确说了”
```

和：

```text
“系统根据上下文推导出来”
```

不应该拥有同样的权重。

以后如果发生冲突：

```text
用户明确预算200
vs
系统曾经根据“太贵了”推导150
```

应该能够知道哪一个更可信，而不是只剩一个数字。

工业级 Memory 通常也强调 scope、source、timestamp、confidence 等元数据。

目前项目只实现了比较小的一部分 provenance：

```text
USER_EXPLICIT / SYSTEM_DEFAULT / DERIVED
```

并没有做完整 confidence / timestamp per field，这属于工业级差距。

## 二、状态一致性、并发和架构追问

**Q11：Working Memory 是怎么持久化的？为什么不用直接 UPDATE 一行？**

当前不是：

```sql
UPDATE working_memory
SET json = ...
WHERE chat_id = ?
```

而是：

```text
chatId
version 1
version 2
version 3
...
```

每次状态变更 append 一个完整 Snapshot。

`WorkingMemoryVersionService.append()` 会先读取最新版本：

```java
actualVersion
```

然后和调用方携带的：

```java
expectedVersion
```

比较。

不一致直接抛：

```text
VersionConflictException
```

一致才插入：

```text
version = expectedVersion + 1
```

同时数据库 `(chat_id, version)` 唯一约束还会兜最后一层并发竞争。

而 `ConversationStateService` 每次成功提交后，会把：

```text
state.version
state.workingMemoryJson
```

更新成新提交版本。

这就是一个比较典型的 Optimistic Concurrency Control。

------

**Q12：为什么使用 Full Snapshot，而不是 Event Sourcing？**

这点不要答错。

当前系统：

```text
不是 Event Sourcing
```

虽然存在：

```text
ConversationEvent
```

但真正的业务真值是：

```text
WorkingMemory Full Snapshot
```

Event 主要用于：

```text
审计
诊断
Trace
```

不是靠重放 Event 重建当前状态。

我当时选择 Full Snapshot 的原因是：

第一，ConversationWorkingMemory 数据量目前很小。

第二，读取当前状态非常频繁，如果 Event Sourcing：

```text
几十个事件
→ replay
→ 才得到 current state
```

增加了实现和排错成本。

第三，当前项目真正需要的是：

```text
可恢复
可审计
并发可检测
```

而不是严格的事件溯源模型。

Full Snapshot + OCC 已经够用。

而且历史版本并没有浪费。

项目支持读取某个历史版本：

```java
get(chatId, version)
```

恢复时也不是：

```text
把历史版本改成 current
```

而是：

```text
读取旧 Snapshot
→ 验证
→ append 一个新的版本
```

历史仍然不可变。

所以更准确的说法是：

> **Versioned Snapshot + Runtime Event Audit**

不要说：

> “我实现了 Event Sourcing。”

------

**Q13：如果两个请求同时修改同一个会话怎么办？**

第一层是 Working Memory OCC：

```text
Request A：base version = 10
Request B：base version = 10
```

A 先成功：

```text
10 → 11
```

B 再提交时：

```text
expected=10
actual=11
```

直接冲突，不允许 silent overwrite。

但项目还有第二层非常值得讲。

假设某个 Tool 很慢：

```text
Turn A
开始执行 Tool
base WM version = 20

与此同时

Turn B
把 Working Memory 更新到 21

然后 Turn A 的 Tool 才返回
```

如果直接把 Turn A 的结果写进去：

```text
旧结果
覆盖
新状态
```

这也是典型 stale write。

现在 `AgentSessionContext` 会记录：

```text
baseWorkingMemoryVersion
```

Tool 结果准备回写时，`applyAgentContext()` 会重新检查 latest version。

如果不一致：

```text
STALE_RUNTIME_RESULT
```

并抛：

```text
StaleRuntimeResultException
```

不会让慢请求覆盖新状态。

这个点我认为非常值得准备。

因为面试官很可能追问：

> “乐观锁只能保护写入本身，那一个 5 秒前启动的 Tool 结果回来怎么办？”

我们现在是有真实代码答案的。

------

**Q14：那你前面说的“同一 Turn 必须同一份 Snapshot”，和 OCC 不是重复了吗？**

不是。

这是两个不同层级的问题。

OCC 解决的是：

```text
Request A
vs
Request B
```

也就是跨请求并发。

而当时 Task V2 的 Bug 是：

```text
同一个 Request 内
Node A
vs
Node B
```

即使根本没有并发，也会出错。

比如：

```text
Pipeline Memory version 10

transitionTask()
→ 在内存里创建 Task B

reduceCriteria()
→ 又去 DB load version10

Task B 没了
```

数据库 OCC 根本看不出来，因为这两个操作甚至可能还没有形成冲突写。

所以：

```text
Request-scoped coherent snapshot
```

和：

```text
cross-request OCC
```

是两套互补机制。

可以概括成：

> 同一 Turn 内保证“看的是同一个世界”；不同 Turn 并发时保证“旧世界不能覆盖新世界”。

------

**Q15：状态更新和 Event 为什么要同事务？**

`WorkingMemoryVersionService.append()` 是：

```java
@Transactional
```

写入：

```text
Working Memory Version
```

以后，还会写一条对应的 State Event。

如果 Event 插入失败：

```text
整个事务回滚
```

所以不会出现：

```text
Memory version11 已经存在
但是 STATE_REDUCED event 不存在
```

这种状态真值和审计记录分叉的问题。

但不是所有 Event 都必须这么严格。

例如：

```text
普通 Rewrite
Routing diagnostics
Telemetry
```

可以 best-effort。

这里体现的是一个边界：

```text
改变业务真值的状态事件
→ durable / transaction-bound

纯诊断事件
→ best-effort
```

------

**Q16：为什么后来 Single Action Contract 又出了问题？TurnPlan 是干什么的？**

原来每轮只有一个：

```text
ChatProcessingAction
```

比如：

```text
START_DECISION
BUSINESS_FOLLOW_UP
GENERAL_CHAT
...
```

这意味着系统默认：

> 一轮用户输入只能做一件事情。

但用户会说：

```text
第一家太贵了，第二家有插座吗？
```

这句话实际上同时包含：

```text
State Mutation：
第一家太贵
→ 收紧预算

Execution：
查询第二家是否有插座
```

如果只允许一个 Action：

选择：

```text
START_DECISION
```

就回答不了插座问题。

选择：

```text
BUSINESS_FOLLOW_UP
```

又会吞掉预算 Mutation。

最后没有设计成：

```java
List<Action>
```

因为 Criteria Mutation 和 Tool Execution 根本不是同一种东西。

而是拆成两个正交维度：

```java
public class TurnPlan {
    private CriteriaIntent criteriaIntent;
    private ChatProcessingAction executionAction;
}
```

其中 CriteriaIntent 目前只有：

```text
NONE
APPLY_DELTA
```

所以可以表达：

```text
普通推荐：
APPLY_DELTA + START_DECISION

纯事实追问：
NONE + BUSINESS_FOLLOW_UP

复合输入：
APPLY_DELTA + BUSINESS_FOLLOW_UP
```

当前 `CriteriaReductionNode` 也不再判断：

```text
action == START_DECISION
```

而是判断：

```java
turnPlan.criteriaIntent == APPLY_DELTA
```

这是一个很重要的架构演进：

> Mutation 是状态维度，Action 是执行维度，两者不能混成一个枚举。

------

**Q17：Compound 场景下为什么必须先解析“第二家”，再修改预算？**

因为用户说：

```text
第一家太贵了，第二家有插座吗？
```

“第一家”“第二家”指的是：

> 用户说这句话时屏幕上看到的 RecommendationBatch。

所以正确顺序必须是：

```text
Context Rewrite
↓
第一家 → shopA
第二家 → shopB
↓
固化 ResolvedShopReference

然后

Criteria Reduction
↓
因为第一家太贵
修改预算
↓
可能 invalidate 当前 candidate pool

最后

Business Follow-up
↓
仍然查询已经固化的 shopB
```

不能在 Mutation 之后重新解析：

```text
“第二家”
```

否则候选池已经失效甚至换了 Batch，“第二家”可能指向另一个实体。

所以这是一个因果不变量：

> Reference 的语义属于用户发话时刻的 Pre-Mutation Snapshot。

目前代码已经按照这个时序工作：Context Rewrite 在 Criteria Reduction 前；`TurnPlan` 只是决定后续 mutation 和 execution，并不会让 Execution 重新动态绑定 ordinal。

------

**Q18：你的实现和 LangGraph / OpenAI Agents SDK 的 Memory 有什么关系？**

这个问题不要回答成：

> “我们和 LangGraph 差不多。”

应该具体分。

OpenAI Agents SDK 的 Session 主要帮开发者维护：

```text
conversation items
```

每轮自动读取历史并追加新的 user/assistant items。

这更接近我们：

```text
ChatMemoryService
```

而不是完整的：

```text
ConversationWorkingMemory
```

LangGraph 的方向更接近我们现在做的事情。

它把 short-term memory 当作：

```text
thread-scoped agent state
```

再通过 checkpointer 把 State 持久化。

LangGraph 的 Persistence 还会在每个 graph super-step 保存 checkpoint，可以拿到历史 StateSnapshot，用于恢复、time travel、human-in-the-loop 和 fault tolerance。

我们现在相当于自己实现了一套业务特化版本：

```text
thread
≈ chatId

state
≈ ConversationWorkingMemory

checkpoint
≈ versioned full snapshot

history
≈ tbl_ai_working_memory versions
```

但是不能说两者完全一样。

最大的区别是：

```text
LangGraph
→ 通用 Workflow/Graph Runtime
→ 每个 super-step checkpoint
→ 可以恢复 graph execution

我们的实现
→ 餐饮业务特化
→ 主要在业务状态变化时 snapshot
→ 不能通用恢复 Pipeline 任意 Node 的执行位置
```

这也是工业级差距之一。

Microsoft Agent Framework 的 Workflow Checkpoint 同样是完整保存 workflow state，并支持从 checkpoint 恢复执行。

所以如果项目未来演化成长任务 Agent：

```text
几十个 Step
人工审批
中断恢复
长时间 Tool
```

那我不会继续无限扩当前这一套。

会考虑：

```text
LangGraph
Temporal
Microsoft Agent Framework Workflow
```

这类 Durable Workflow Runtime。

但当前是同步餐饮决策，多数 Turn 在一次请求内结束，所以自己维护业务 State 的复杂度仍然可控。

------

**Q19：为什么不用 LangGraph，自己写这么多？**

首先这个项目本身是 Java / Spring Boot 项目。

第二，我们真正需要的不是一个复杂自由 Agent Loop，而是一条高度受约束的业务 Pipeline：

```text
Rewrite
→ Routing
→ Criteria Reduction
→ Policy
→ Execution
```

其中很多阶段：

```text
状态归约
候选失效
版本控制
位置安全
```

本来就应该是确定性 Java 逻辑。

如果为了使用 Agent Framework，反过来把这些规则全部塞进：

```text
LLM Node
Conditional Edge
```

不一定更合理。

所以现在的选择更像：

> 确定性流程使用 Workflow/Pipeline；只有自然语言理解和开放式工具选择才使用模型。

这也是成熟 Agent 设计里比较常见的原则：固定业务步骤不应该为了“Agent 化”而强行全部模型化。

当然，如果未来出现：

```text
动态几十步任务
多个分支
人工暂停
跨小时恢复
复杂补偿
```

当前 Pipeline + 手写 Snapshot 就不再是最经济的方案。

------

**Q20：你怎么证明 Working Memory 的修改是真的有效，而不是你自己觉得架构更漂亮？**

这里必须讲 Evaluation，而不是讲设计图。

最重要的一组是 Task Scope：

```text
Flat Working Memory
2/4

Task V2 初版
2/4

修复同轮 Snapshot 一致性
4/4
```

但在 Task Scope 之前，还应该讲早期候选状态问题。

早期 Evaluation 暴露过类似问题：

```text
换一批后重复推荐
历史候选被覆盖
“之前第二家”无法稳定定位
条件修改后仍然沿用旧候选
```

这些问题推动了：

```text
candidatePool / shownPool 分离
→ RecommendationBatch
→ Candidate Invalidation
```

之后，Task Scope Evaluation 又暴露了：

```text
A → B → A
A → B → C → A
```

中的状态覆盖问题。

测试的不是：

```text
Task 类有没有创建成功
```

而是真正跑：

```text
A → B → A
A → B → C → A
A → B → A + 新预算
Location Refinement
换一批
历史批次引用
条件修改后的候选失效
```

验证：

```text
taskCount
activeTaskId
criteria
location
budget
candidate relation
shown relation
batch reference
```

开发记录中保留了完整的 Run 演进。

后来完整 robustness 从：

```text
Run95 Complete 6/24
```

提升到：

```text
Run116 Complete 21/24
Route 22/24
Tool 23/24
Final Status 23/24
```

剩余三个 Location fixture Case 在最终修改后定向 Run119 为：

```text
3/3 PASS
```

但这里面试时一定要注意口径：

> 当前最终 HEAD 并没有在最后两行 fixture 修改后重新跑一次完整 24 Case。

所以现在不能直接说：

```text
6/24 → 24/24
```

最严谨的说法是：

> 完整 robustness 由 6/24 提升到 21/24，最后剩余三个地点依赖用例在最终 fixture 补全后定向 3/3；最终 HEAD 的完整 24 Case 仍需要补跑一次才能正式归档最终 Complete Rate。

这是必须坚持的真实性边界。

------

**Q21：如果让你现在评价这套 Working Memory，它离工业级还有什么差距？**

我认为目前最大的差距不是“还少几个字段”，而是下面这些。

第一，**Working Memory 是后期演化出来的，早期状态模型仍然留下了历史痕迹。**

当前已经有：

```text
ConversationWorkingMemory
DecisionTaskState
RecommendationBatch
```

但早期的：

```text
candidatePool
shownPool
focusedShop
```

等概念仍然需要明确哪些是 canonical state，哪些只是 projection 或兼容字段。

如果继续演化，应该进一步明确：

```text
RecommendationBatch
= 历史事实

latestCandidatePool
= 当前投影

shownShopIds
= 历史展示投影
```

避免多个字段都被误认为可以直接修改的真值。

------

第二，**Task Identity 仍然偏启发式。**

现在主要依赖：

```text
city + area + cuisine
```

以及：

```text
city 和 cuisine 同时变化 → CREATE
```

这适合当前餐饮决策 Dataset，但不具备通用 Task Management 能力。

如果业务复杂，应该进一步把：

```text
Task switch / create / resume
```

变成一个明确的结构化领域 Intent。

------

第三，**历史 Task 引用仍存在字符串语义下沉。**

`ConversationStateService` 现在仍判断：

```text
最开始
之前
上一个
```

这是当前 Working Memory 主线上我认为最值得继续审查的真实问题。

正确边界更应该是：

```text
NL
↓
TaskReferenceIntent
↓
TaskResolver
↓
ConversationStateService 只执行状态操作
```

这和我们后来 ReferenceIntent 的演进方向一致。

------

第四，**Full Snapshot 存储会持续增长。**

现在每一次业务状态改变都会追加完整：

```text
ConversationWorkingMemory JSON
```

而 Task 中又包含：

```text
recommendationBatches[]
```

长对话下 Snapshot 会越来越大。

仓库自己的 Known Limitation 里也已经记录 Working Memory version chain 没有 TTL / archive。

当前项目数据规模很小，所以 Full Snapshot 更容易调试，我认为是合理 trade-off。

真到大规模会考虑：

```text
保留最近 N 个 Full Snapshot
+
更老版本归档
+
Snapshot Compression
+
Delta / periodic full checkpoint
```

而不是现在提前实现。

------

第五，**Checkpoint 粒度不是 Durable Workflow 级。**

当前可以恢复：

```text
业务 Working Memory
```

但不能恢复：

> Pipeline 正执行到哪个 Node、哪个 Tool 已完成、哪个还没完成。

所以我们现在应该准确描述：

> 支持业务状态版本恢复。

而不是：

> 支持任意 Agent Execution 的故障恢复。

------

第六，**字段级 provenance 还比较简单。**

目前只有：

```text
USER_EXPLICIT
SYSTEM_DEFAULT
DERIVED
```

没有：

```text
timestamp
confidence
sourceEventId
expiration
conflict state
```

对于当前餐饮场景够用。

如果做真正长期用户 Memory 或金融、医疗等高风险领域，这就明显不够。

------

第七，**schemaVersion 有了，但还没有形成完整 Schema Migration Framework。**

当前：

```java
schemaVersion = 2
```

并做了旧 Flat Working Memory 向 Task 模型的兼容归一。

但如果未来：

```text
v2
→ v3
→ v4
```

会需要更明确的：

```text
MemoryMigrator<Vn,Vn+1>
```

而不是不断把兼容逻辑塞进 normalize。

当前规模下还没必要提前造这个框架。

------

**Q22：如果面试官问“那你认为 Working Memory 这条线最有价值的地方是什么？”**

我会回答：

不是我设计了多少状态字段。

真正有价值的是，我从一个最早没有 Working Memory 的实现开始，通过真实 Bad Case 逐步建立了几个状态系统的不变量。

第一个：

```text
Chat History 不是业务真值。
```

第二个：

```text
candidatePool 和 shownPool 不是同一个概念。
```

第三个：

```text
当前候选可以失效，但历史展示事实不能被删除。
```

第四个：

```text
一个 Conversation 可以同时存在多个独立 Decision Task。
```

第五个：

```text
Task 当前候选可以失效，但用户看到过的历史 RecommendationBatch 不能因此消失。
```

第六个：

```text
Reference 必须绑定到用户发话时刻的 Snapshot。
```

第七个：

```text
同一 Turn 内 transition → merge → reduce 必须使用同一份 Working Memory Snapshot。
```

第八个：

```text
跨 Turn 并发依靠 version OCC，旧 Runtime Result 不允许覆盖新 Memory。
```

第九个：

```text
Criteria Mutation 和 Execution Action 是正交维度，不能强塞进一个 Action。
```

我认为这些东西比：

```text
用了 Redis
用了 Spring AI
用了 Milvus
```

更能说明我真正理解了 Agent 的状态管理问题。

## 三、这一阶段我们真正要继续准备什么

接下来 Working Memory 主线不用再漫无目的开发。

应该围绕下面几件事继续吃透：

1. 能不看代码，完整画出：

```text
ChatProcessingContext
ConversationWorkingMemory
DecisionTaskState
RecommendationBatch
DecisionSession
Runtime Event
```

之间的关系。

1. 能讲清楚项目的演进时间线：

```text
没有 Working Memory
→ candidatePool / shownPool 分离
→ RecommendationBatch
→ Flat Working Memory
→ Task Scope
→ Request-scoped Snapshot
→ OCC / stale result protection
```

1. 能手推：

```text
A → B → A
```

每一轮 Working Memory 到底发生了什么。

1. 能手推：

```text
第一家太贵了，第二家有插座吗？
```

Reference、Criteria Delta、Candidate Invalidation、TurnPlan 和 Tool Execution 的时序。

1. 能解释：

```text
为什么 candidatePool 和 shownPool 必须分开。
```

1. 能解释：

```text
为什么 RecommendationBatch 不能被一个 shownPool 替代。
```

1. 能解释：

```text
为什么 Request Snapshot 和 OCC 不重复。
```

1. 能解释：

```text
为什么 Full Snapshot 不是 Event Sourcing。
```

1. 能解释：

```text
为什么 Chat History 不能取代 Working Memory。
```

1. 能明确说出：

```text
当前 Task Identity 为什么这么设计，
什么地方仍然只是启发式。
```

1. 能明确说出和 LangGraph/OpenAI Session 的相同点和不同点，而不是只会说“类似”。
2. 最终 HEAD 再完整执行一次 robustness 24 Case，正式归档最终指标。
3. 对当前发现的：

```text
Task Historical Reference NLP 下沉
```

单独做一次架构审查，再决定值不值得改。

1. 对早期状态字段做一次 canonical state 审计，明确：

```text
哪些是事实
哪些是 projection
哪些只是兼容字段
```

这十四件事情完成以后，我认为 Working Memory 主线才真正接近“面试可用”。

------

最后有几句话是面试中的红线。

不要说：

> “我的 Memory 就是 Redis 聊天记录。”

不要说：

> “我一开始就设计好了 Working Memory。”

不要说：

> “candidatePool 和 shownPool 本来就是一个东西。”

不要说：

> “我做了 Event Sourcing。”

不要说：

> “我的系统是生产级 Agent。”

不要说：

> “Robustness 已经 24/24。”

不要说：

> “用了 Task 就完全解决了多轮状态问题。”

更准确的表达应该是：

> 我针对当前消费决策场景建立了 Task-scoped Working Memory，但它不是一开始就存在的，而是从早期没有 Working Memory、候选池与展示池混淆、历史批次丢失等问题中逐步演化出来的。现在通过版本化 Snapshot、OCC、RecommendationBatch、Candidate Invalidation 和 Evaluation，解决了已识别的多轮状态污染、历史引用和并发覆盖问题；它已经形成一套可解释、可测试的业务状态模型，但 Task Identity、Snapshot 生命周期、早期字段收敛和 Durable Execution 能力仍然有明确的工程边界。