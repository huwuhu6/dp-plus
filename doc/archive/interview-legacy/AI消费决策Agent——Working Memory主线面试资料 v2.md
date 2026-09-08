# AI 消费决策 Agent——Working Memory 主线面试资料 v2

> 当前 Code Truth：main `33d37e1d494ad3e03d53ebc0ce9be0d50e7a5805`。
>
> 当前阶段最新全量评测：Robustness Run 137、Conversation-v1 Run 138；Holdout 尚未重新执行。
>
> 这份资料是当前面试主入口。目标不是把项目包装成“生产级通用 Agent”，而是让你能从真实问题、状态模型、架构取舍、业内方案、评测与局限几个维度，把当前项目讲完整、讲准确、经得起追问。
>
> RAG 检索链路、SSE、Tool Planner 不在本文展开；本文聚焦 Working Memory、Task、RecommendationBatch、Reference、TurnPlan、Location Contract、Decision Context Query、版本化状态和 Evaluation。

---

# 一、30 秒 / 2 分钟 / 5 分钟项目介绍

## Q1：30 秒怎么介绍？

我做的是一个本地生活餐饮消费决策 Agent，重点不是单轮推荐，而是多轮状态管理。用户可以连续说“福州火锅人均100”“换杭州日料”“还是最开始那套”“第一家太贵了，第二家有插座吗”，系统需要稳定维护任务、约束、历史推荐批次和引用关系。

项目最早没有真正的 Working Memory，主要靠聊天历史和临时上下文。后来通过 Evaluation 持续暴露候选覆盖、方案串状态、历史引用、同轮状态快照不一致、位置语义混淆等问题，逐步演化出 Task-scoped Working Memory、RecommendationBatch、ReferenceIntent、TurnPlan、版本化 Snapshot/OCC，以及行政区 Resolver 和只读决策上下文查询。

一句话总结：

> 我主要解决的是“多轮 Agent 如何让业务状态成为可持久化、可追溯、可并发控制、可评测的确定性状态，而不是每轮都让模型重新猜历史”。

---

## Q2：2 分钟怎么介绍？

聊天入口是一条显式 Pipeline：

```text
Bootstrap
→ Context Rewrite
→ Intent Routing
→ Criteria Reduction
→ Policy Guard
→ Execution
```

模型主要负责开放式语义：约束抽取、部分路由、ReferenceIntent；Java 负责确定性业务约束：状态归约、Task 切换、Candidate Invalidation、版本并发控制、Location Authority 和只读 Context Query。

最早系统没有 Canonical Working Memory，状态散落在 Chat History、当前请求 Context、candidatePool、focusedShop 等地方。Evaluation 先暴露“换一批后历史候选被覆盖”，于是拆 candidatePool / shownPool；后来又发现 shownPool 无法回答“最开始第二家”，于是增加 RecommendationBatch；再后来 A→B→A 多方案切换出现 Ghost Inheritance，Flat Working Memory 无法保存多个方案，于是引入 Task Scope；Task V2 第一版又因为同一个请求里重新 load 旧 Working Memory，导致新 Task 被旧 Snapshot 覆盖，于是建立“一次 Turn 只能使用一个 authoritative Working Memory Snapshot”的不变量。

之后继续补了 OCC 和 stale runtime result 防护；用 TurnPlan 把 Criteria Mutation 与 Execution Action 拆开；Location 从字符串逐步拆成 Value / Intent / Provenance / Projection，并把行政区从 LLM 语义理解中剥离成 closed-world entity resolution；最后为了回答“我之前说过安静吗”“为什么推荐第一家”“刚才查的是哪里”，增加 `DECISION_CONTEXT_QUERY`，严格从 Canonical State 和历史 Decision Fact 中只读投影，不让 LLM 从聊天记录重猜。

---

## Q3：如果面试官让你讲 5 分钟，主线怎么铺？

推荐按下面顺序：

```text
1. 业务场景：多轮餐饮决策
2. 最早没有 Working Memory
3. candidatePool / shownPool / RecommendationBatch
4. Flat WM → Task Scope → A/B/A Ghost Inheritance
5. 同 Turn Snapshot 一致性
6. OCC + stale runtime result
7. Reference + TurnPlan
8. Location Contract / Administrative Authority
9. Decision Context Query / Provenance / Historical Fact
10. Evaluation 如何推动演进
11. 当前边界与工业级差距
```

不要开场就报：

```text
Spring AI / Milvus / Redis / Function Calling
```

这会让项目听起来像“组件拼装”。

---

# 二、State / Context / Memory：牛客最容易先问穿的一组

近期牛客 Agent 面经里，“State、Context 与 Memory 区别”“上下文窗口满了怎么办”“短期记忆和长期记忆如何设计”“为什么不用聊天历史直接做 Memory”都是高频问题。你这套项目正好可以用真实代码回答，而不是背概念。

## Q4：State、Context、Memory 有什么区别？

可以这样答：

```text
State
= 应用当前保存的业务事实

Context
= 本次模型 / Tool / Pipeline 实际能看到的信息

Memory
= 为未来 Turn 或未来会话保留下来的信息
```

在本项目中：

```text
ConversationWorkingMemory
→ Canonical Business State

ChatProcessingContext
→ 当前 Turn 的 Runtime Context

Chat History
→ 对话语言历史

RecommendationBatch / DecisionSession
→ 历史业务事实
```

关键点：

> 数据库里“存着”不等于模型本轮“看见”；聊天里“说过”也不等于系统当前仍认为它有效。

---

## Q5：为什么 Chat History 不能替代 Working Memory？

Chat History 保存：

> 用户和模型说过什么。

Working Memory 保存：

> 系统当前相信哪些业务事实仍然有效。

例如：

```text
用户：预算 100
用户：算了预算不限
用户：还是 100 吧
```

如果只有 History，每一轮都要重新理解整个历史才能决定当前预算。问题包括：

```text
旧条件和新条件竞争
清除语义难表达
模型每轮解释可能不一致
Token 持续增长
业务代码无法直接读取当前真值
并发没有 canonical version
历史批次和 ordinal reference 难稳定恢复
```

所以：

```text
Chat History = Language Context
Working Memory = Business Truth
```

---

## Q6：业内怎么做？你的方案和 OpenAI / LangGraph / Microsoft 有什么关系？

不要说“我们和 LangGraph 差不多”，要拆开讲。

### OpenAI Agents SDK

OpenAI Agents SDK 的 Session 主要是会话持久化：按 session 保存 conversation items，在下一次 run 自动取回历史并追加新消息。

它更接近本项目的：

```text
Chat History / ChatMemoryService
```

而不是完整业务 `ConversationWorkingMemory`。

### LangGraph

LangGraph 把 short-term memory 作为 thread-scoped Agent State，通过 checkpointer 保存 State Snapshot；它还能获取历史 checkpoint，用于 resume、time travel、human-in-the-loop 和 fault tolerance。

可以类比：

```text
LangGraph thread_id
≈ chatId

LangGraph State
≈ ConversationWorkingMemory（抽象层面）

checkpoint
≈ versioned snapshot
```

但区别是：

```text
LangGraph
→ 通用 Graph Runtime
→ super-step checkpoint
→ 能恢复执行位置

本项目
→ 餐饮业务特化状态
→ 业务状态变化时 snapshot
→ 不恢复任意 Pipeline Node 的执行位置
```

### Microsoft Agent Framework / Semantic Kernel

Microsoft 的 AgentThread / AgentSession 同样把 conversation state 与 Agent invocation 解耦；workflow 方向还支持 checkpoint / resume。

你的回答重点不是“谁更先进”，而是：

> 通用框架负责 thread/session/checkpoint 基础设施；本项目自己实现的是业务特化的 canonical state，因为“预算、Task、RecommendationBatch、行政范围、Constraint Provenance”属于产品领域状态，并不会因为有一个 Session API 就自动建模出来。

---

## Q7：为什么不用 LangGraph，自己写？

当前是 Java / Spring Boot 项目，而且主流程高度确定：

```text
Rewrite
→ Routing
→ Criteria Reduction
→ Policy
→ Execution
```

其中：

```text
状态归约
候选失效
版本检查
位置安全规则
历史引用
```

本身就适合确定性 Java 逻辑。

如果为了“Agent 化”把这些都改成 LLM Node / Conditional Edge，并不会更可靠。

当前取舍：

```text
固定业务流程 → Pipeline / Workflow
开放语义理解 → LLM
外部事实 → Tool
Canonical State → Java deterministic reducer
```

如果未来变成跨小时、几十步、人工审批、可中断恢复的长任务，再考虑 LangGraph / Durable Workflow Runtime 更经济。

---

# 三、Working Memory 是怎么一步步长出来的

## Q8：最开始没有 Working Memory 时是什么样？

最早状态散落在：

```text
Chat History
ChatProcessingContext
candidatePool
shownPool
focusedShop
Conversation fields
```

简单 refinement 可以做，但多批次、多方案、历史引用开始崩。

第一批真实问题：

```text
推荐 A B C
→ 换一批
→ 推荐 D E F
```

当前候选需要变成 D/E/F，但系统又必须记住 A/B/C 已经展示过。

因此先拆：

```text
candidatePool
= 当前可继续决策的候选

shownPool
= 用户历史看过的候选
```

---

## Q9：为什么 shownPool 还不够，必须 RecommendationBatch？

因为：

```text
shownPool=[A,B,C,D,E,F]
```

只能回答“看过谁”，不能回答：

```text
第一批第二家是谁？
第二批第一家是谁？
```

所以增加：

```text
RecommendationBatch1=[A,B,C]
RecommendationBatch2=[D,E,F]
```

最终：

```text
RecommendationBatch[]
├─ latest batch → latest candidate projection
└─ all batches → shown shop union
```

历史批次本身才是更完整的 historical fact。

---

## Q10：Flat Working Memory 为什么失败？什么叫 Ghost Inheritance？

Flat WM 只有一套当前条件：

```text
福州火锅100
→ 杭州西湖日料300
→ 还是最开始那套
```

当第二套覆盖第一套后，再回来很容易拼成：

```text
福州 + 火锅 + 300 + 西湖
```

旧方案的一部分和当前方案的一部分互相继承，这就是 Ghost Inheritance。

所以引入：

```text
ConversationWorkingMemory
└─ tasks[]
   ├─ Task A: 福州 / 火锅 / 100
   └─ Task B: 杭州 / 日料 / 300
```

恢复 A 是 re-activate Task A，而不是用 B + 当前一句话重新拼 A。

---

## Q11：Task 怎么 CREATE / UPDATE / SWITCH？预算为什么不做 Task Identity？

当前是业务启发式，不是通用 Task 理解算法。

大方向：

```text
无 active task → CREATE
明确历史 Task reference → SWITCH
唯一匹配历史 signature → SWITCH
地点 + 菜系发生足够大的组合变化 → CREATE
其他 refinement → UPDATE
```

预算通常属于约束，不属于“我在考虑哪套方案”的身份。

否则：

```text
福州火锅100
福州火锅80
福州火锅120
```

会碎成三个 Task。

要诚实承认：Task Identity 仍是当前餐饮业务的启发式规则，不是通用 Agent Task Manager。

---

## Q12：Task V2 为什么单测过了，E2E 还是失败？

这是项目最值得讲的坑之一。

当时：

```text
transitionTask()
→ 修改 pipeline 内存中的 WM

reduceCriteria()
→ 又从 DB reload 同 version 旧 WM
```

于是新建 Task 在同一个请求里被旧 Snapshot 覆盖。

所以最终不变量：

```text
一个 Turn
只能有一个 authoritative Working Memory view
```

流程：

```text
bootstrap snapshot
→ task transition
→ merge
→ reduce
→ persist
```

必须基于同一 request-scoped snapshot。

这和 OCC 不一样：

```text
Snapshot consistency
→ 同一个 Request 内不同 Node

OCC
→ 不同 Request 之间的并发
```

一句话：

> 同一 Turn 看同一个世界；并发 Turn 不允许旧世界覆盖新世界。

---

# 四、持久化、OCC、Stale Result：后端面试官很容易追

## Q13：为什么 Working Memory 不是直接 UPDATE 一行？

当前使用 versioned full snapshot：

```text
chatId / version1
chatId / version2
chatId / version3
```

写入时：

```text
expectedVersion
vs
actualVersion
```

不一致：

```text
VersionConflictException
```

一致才 append `expected+1`。

数据库 `(chat_id, version)` 唯一约束还兜底最后一层竞争。

这是 OCC。

---

## Q14：为什么 Full Snapshot，不做 Event Sourcing？

当前不是 Event Sourcing。

真正业务真值：

```text
WorkingMemory Snapshot
```

Runtime / Conversation Event 主要用于：

```text
审计
诊断
Trace
```

不会靠 replay Event 重建当前 State。

当前状态对象体量可控，Full Snapshot 的优点：

```text
读取简单
恢复简单
排错简单
版本对比直接
```

成本是长会话存储会增长。

准确名称：

> Versioned Snapshot + Runtime Event Audit。

---

## Q15：两个请求同时修改怎么办？

```text
A baseVersion=10
B baseVersion=10
A 成功 10→11
B 提交 expected=10, actual=11
→ reject
```

不是 last-write-wins。

---

## Q16：慢 Tool 5 秒后回来，OCC 能解决吗？

仅写入时 OCC 还不够。

例如：

```text
Turn A 开始 Tool，base WM=20
Turn B 更新 WM=21
Turn A Tool 返回
```

如果直接应用 A 的 Runtime Result，就可能用旧世界覆盖新世界。

所以 `AgentSessionContext` 记录 `baseWorkingMemoryVersion`，回写时重新校验 latest version；不一致标记 `STALE_RUNTIME_RESULT` 并拒绝应用。

这个回答非常适合 Java 后端面试官继续追“乐观锁保护的边界”。

---

## Q17：为什么状态版本和 State Event 要同事务？

改变业务真值的事件要 durable：

```text
WM append
+
State Event
→ 同一事务
```

否则会出现：

```text
状态已经是 v11
但审计事件还停在 v10
```

而 Rewrite / Routing diagnostics 等纯观测事件可以 best-effort。

核心：

```text
Business Truth Event → transaction-bound
Telemetry Event → best-effort
```

---

# 五、Reference 与 TurnPlan：多轮复合输入怎么避免一团乱

## Q18：为什么 ReferenceIntent 要结构化？

用户会说：

```text
第一家
第二家
刚才那个
最开始第一家
```

不应该让每个业务 Handler 自己写 `contains("第一家")`。

统一链路：

```text
Natural Language
→ ReferenceIntent
→ BatchAwareReferenceResolver
→ ResolvedShopReference
```

这样引用解析只有一个 authority。

---

## Q19：“第一家太贵了，第二家有插座吗”为什么 Single Action Contract 不够？

一句话同时有：

```text
Criteria Mutation
第一家太贵 → budget change

Execution
第二家有插座吗 → merchant fact query
```

如果一个 Turn 只有一个 Action：

```text
START_DECISION
```

会吞事实查询；

```text
BUSINESS_FOLLOW_UP
```

会吞状态修改。

所以拆成：

```text
TurnPlan
├─ CriteriaIntent: NONE / APPLY_DELTA
└─ ExecutionAction
```

这是“状态维度”和“执行维度”正交化，不是简单 `List<Action>`。

---

## Q20：为什么必须 Pre-Mutation Resolve Reference？

用户发话时看到的是旧 Batch：

```text
第一家太贵，第二家有插座吗
```

正确时序：

```text
Context Rewrite
→ 第一家=ShopA
→ 第二家=ShopB
→ 冻结 reference

Criteria Reduction
→ 修改预算
→ invalidate current candidate projection

Execution
→ 仍查询冻结的 ShopB
```

如果 mutation 后重新解析“第二家”，候选池可能已变，引用会漂移。

核心不变量：

> Reference 属于用户发话时刻的 pre-mutation snapshot。

---

# 六、Decision Context Query：Memory 不只是“记住”，还要能解释

## Q21：为什么增加 `DECISION_CONTEXT_QUERY`？

真实用户会问：

```text
为什么推荐第一家？
我之前有说过安静吗？
当前按什么条件找？
你刚刚是查哪里的？
```

这些都不是新的推荐请求，也不是商户外部事实查询，而是：

> 对已有 Decision State / Historical Fact 的只读查询。

所以顶层只有一个：

```text
DECISION_CONTEXT_QUERY
```

当前 QueryType：

```text
WHY_RECOMMENDED
CONSTRAINT_PROVENANCE
CURRENT_CRITERIA
EXECUTED_SEARCH_SCOPE
```

不为每种问法新增顶层 Route。

---

## Q22：为什么不能让 GENERAL_CHAT 直接读聊天历史回答？

因为会出现双真相：

```text
Canonical State
vs
LLM 重新扫描 History 后的“回忆”
```

例如 `preferences=[安静]` 只能证明安静当前生效，不能证明用户明确说过安静。

来源可能是：

```text
USER_EXPLICIT
SYSTEM_DEFAULT
DERIVED
```

因此“我说过安静吗”必须读取 `constraintSources`，不能靠 History 猜。

---

## Q23：为什么 Preference Provenance 要按 value 记录？

不能：

```text
preferences → USER_EXPLICIT
```

因为：

```text
安静 → USER_EXPLICIT
约会 → DERIVED
不排队 → USER_EXPLICIT
```

同一个 List 里来源不同。

所以：

```text
preference:安静
preference:约会
```

做 element-level provenance。

---

## Q24：USER_EXPLICIT 和 DERIVED 怎么区分？

来源必须在“产生值的语义层”确定，而不能 StateService 事后猜。

例如：

```text
想找安静一点
→ 安静 = USER_EXPLICIT

适合聊天
→ canonical 安静 = DERIVED

想找适合约会的餐厅
→ 约会 = USER_EXPLICIT

和女朋友吃饭
→ canonical 约会 = DERIVED
```

数据流：

```text
Extractor sourceHints
→ Merger sourceUpdates
→ StateService persist
```

StateService 不重新做 NLU。

---

## Q25：为什么推荐这个，Source of Truth 是什么？

历史推荐不能用“当前条件”重新解释。

标准链路：

```text
ResolvedShopReference
→ RecommendationBatch.decisionSessionId
→ ConsumptionDecisionService.getDecision(sessionId)
→ AiDecisionSession.resultJson
→ 当时 Recommendation.matchedReasons / evidence
```

如果第一轮预算150，后来改80，再问“最开始第一家为什么推荐”，必须读第一轮历史 DecisionResponse。

否则就是 Temporal Leakage。

---

## Q26：为什么不把 matchedReasons / evidence 都复制进 RecommendationBatch？

因为：

```text
RecommendationBatch
→ 历史 identity / pointer

AiDecisionSession.resultJson
→ 完整历史 Decision Fact
```

WM 是版本化 Full Snapshot。如果每个 Batch 都复制评论 evidence，每次 snapshot 会重复放大长文本，也制造两个持久化真相。

这是：

> Pointer ≠ Payload Duplication。

---

## Q27：“你刚刚查的是哪里”为什么不是 CURRENT_CRITERIA？

当前 WM 可能已经变化，而用户问的是：

> 刚才那一次 Decision 实际执行了什么 Search Scope。

因此：

```text
EXECUTED_SEARCH_SCOPE
→ active/last DecisionSession
→ persisted DecisionResponse.constraints
```

而不是读取当前 Task.criteria 或 Chat History。

这同样是在防 Temporal Leakage。

---

# 七、Location Contract：这条线怎么从几个字符串变成真正的业务状态

## Q28：Location 最早为什么出问题？

逐步暴露的是不同层次的问题：

```text
地点 Value
≠ Intent
≠ Provenance
≠ Administrative Level
≠ Entity Identity
≠ Execution Projection
```

典型演进：

```text
设备 GPS 和命名目的地混用
→ Value / Intent / Provenance 分开

福建被塞进 city
→ province/city/district 进入 Canonical State

区县和 POI 混用 targetArea
→ targetDistrict / targetArea 分开

模型 tool_calls=[] 后行政语义丢失
→ AdministrativeRegionResolver 独立 authority

partial registry 本地只有一个同名区县
→ 本地唯一 ≠ 全国唯一

LLM NOT_FOUND 路径仍把 targetDistrict 偷渡进 State
→ Administrative Hint 只能是 untrusted candidate

POI 中包含行政 alias，比如“福州大学”包含“福州”
→ substring mention ≠ entity mention
→ resolveHint 统一 grounding authority
```

这才是完整的前因后果。

---

## Q29：最终 Location Contract 是什么？

```text
Province / City / Municipality / District
→ AdministrativeRegionResolver
→ Local Registry
→ AMap District WebService fallback

POI / Landmark / Business Area
→ AMap MCP maps_geo

CURRENT_DEVICE
→ Browser GPS
```

三个 authority 不能互相替代。

一句最重要的话：

> 行政范围越具体，不代表越需要定位；只有“相对用户当前位置”的表达才需要 GPS。

---

## Q30：为什么行政区不用纯 LLM？

行政区是 closed-world entity：

```text
有限集合
level
parent hierarchy
标准名称
adcode
同名歧义
```

适合 Registry / Resolver。

偏好是 open-world semantic：

```text
安静
适合聊天
约会
想吃辣
```

适合 LLM。

所以：

```text
LLM 可以提出行政 candidate hint
但 Resolver 才能确认 administrative identity
```

---

## Q31：有 Resolver 为什么还会出现 Authority Leak？

这是后面真实聊天暴露出的关键问题。

旧流程：

```text
Resolver(raw query) → NOT_FOUND
Model → targetDistrict=XXX县
mergeAdministrativeResolution(NOT_FOUND) → 什么都不做
模型字段继续进入 Canonical State
```

名义上 Resolver 是 authority，实际 NOT_FOUND 路径仍被 LLM 绕过。

修复后：

```text
Model admin field
→ untrusted hint
→ resolveHint(raw query, hint, context)
→ raw-query grounding
→ Registry / Provider authority validation
→ verified identity 才能进入 State
```

工程结论：

> 有一个 Resolver 类，不代表它真的拥有 authority；所有旁路都不能绕过它，authority boundary 才成立。

---

## Q32：为什么 `query.contains("福州")` 也不够？

因为：

```text
“福州大学” contains “福州”
```

但不代表用户明确说了“福州市”行政范围。

这是：

```text
String Mention ≠ Entity Mention
```

所以 grounding 统一进 Resolver，并复用 POI boundary：

```text
福州大学附近 + 福州市 hint
→ 不接受 city identity

福州市福州大学附近
→ 明确行政 city + 独立 POI 可以共存
```

没有为“福州大学”写生产特判，测试只是代表性 Case。

---

## Q33：裸区县为什么不能因为本地只有一条就直接 RESOLVED？

因为 Registry 是 partial：

```text
本地只有一个鼓楼区
≠ 全国只有一个鼓楼区
```

Dataset completeness 本身就是推理前提。

允许确定 identity 需要：

```text
明确 parent
或已有 parent context
或 complete registry
或 authoritative provider 返回可信 hierarchy
```

否则 clarification。

---

## Q34：为什么行政解析失败不默认 GPS？

```text
“连江那边有什么吃的”
```

表达的是显式远端命名目的地，不等于“我附近”。

用户人在杭州时，杭州 GPS 根本无法证明“连江”是哪一个行政实体。

所以：

```text
Named Admin unresolved
→ clarify parent / full location

CURRENT_DEVICE intent
→ request Browser GPS
```

这是两个不同 contract。

---

# 八、Evaluation：不要只会报一个通过率

## Q35：你怎么证明这些架构修改有效？

不是靠“代码更漂亮”，而是靠 Evaluation。

项目里的 Evaluation 会看：

```text
Route
Context Rewrite
Tool
Final Status
Locality
Working Memory / per-turn projection
历史 reference / session relation
```

很多问题不是最终回答错，而是中间状态已经污染。

---

## Q36：最新全量评测是什么？

当前 HEAD `33d37e1d...` 后阶段性跑了：

### Robustness Run 137

```text
Dataset: conversation-robustness-v1
Cases: 48
Status: COMPLETED_WITH_ERRORS
Complete: 18/48
Route: 43/48
Context Rewrite: 7/7
Tool: 46/48
Final Status: 37/48
Locality: 48/48
P50/P95/P99: 18,461 / 38,986 / 43,583 ms
Prompt/Completion Token: 3,849 / 1,023
```

### Conversation-v1 Run 138

```text
Cases: 40
Status: COMPLETED_WITH_ERRORS
Complete: 29/40
Route: 38/40
Tool: 33/40
Final Status: 40/40
Locality: 40/40
Context Rewrite: 2/2
P50/P95/P99: 15,212 / 28,613 / 31,411 ms
Prompt/Completion Token: 4,311 / 1,017
```

Holdout 本阶段尚未重新执行。

---

## Q37：Robustness Complete 18/48，是不是说明系统只成功了 18 条？

不能这么解释。

`Complete` 是一个聚合 contract，可能同时受到：

```text
Route
Tool
Final Status
Working Memory expected projection
session relation
旧 Dataset Ground Truth
```

影响。

Run137 中已发现两类明显评测合同漂移：

1. 裸区县 Ground Truth 仍期望直接执行，而当前安全 contract 规定 partial registry + 无 parent 必须 clarification；
2. 大量旧 `expectedMemory` 与当前 per-turn projection 口径不同，29 条出现 `memoryMatched=false`，不能简单等价成 canonical state 全错。

所以面试里不要回避低 Complete，但要能拆指标和失败聚类。

正确说法：

> 当前 robustness 聚合 Complete 仍偏低，但 Route 43/48、Tool 46/48、Locality 48/48；其中一部分是 Dataset Contract 尚未同步当前安全语义，一部分是真实历史 Task/Compound/Clarification 债务。我没有为了提升数字去改生产规则或反向迎合 Ground Truth。

---

## Q38：Conversation-v1 有没有明显回归？

和旧 Run117 对比：

```text
Route 39/40 → 38/40
Tool 34/40 → 33/40
Final 40/40 → 40/40
```

新增明显失败主要是 `ROBUST_PROBE_TASTE_1` 的开放表达模型路由波动，没有证据能归因到最近行政 grounding 修改。

因此没有继续为了单个波动改生产代码。

---

## Q39：为什么不是每修一个 Bug 就 full regression？

模型评测成本高、慢，而且频繁全量会诱导开发者围着 Dataset 调参数。

当前策略：

```text
小修
→ 相关 Unit + 3~8 targeted E2E

3~5 个相关小修 / 阶段闭环
→ full robustness

WM 阶段闭环
→ robustness + conversation-v1

最终归档
→ robustness + v1 + holdout
```

这是成本、反馈速度和过拟合风险之间的 trade-off。

---

## Q40：怎么证明你没有为了 Case 过拟合？

代码层面持续检查：

```text
CaseCode 特判 = 0
shopId 特判 = 0
具体行政地名生产 hardcode = 0
Ground Truth 不写进生产代码
Reference Resolver 不复制 ordinal 逻辑
Location Resolver 不给每个失败句新增 contains
```

修复目标尽量提升为通用不变量：

```text
Entity Resolution ≠ Domain Intent
String Mention ≠ Entity Mention
Value ≠ Provenance
Current Projection ≠ Historical Fact
Partial Dataset ≠ Complete World
Read Query ≠ Mutation
```

真正有没有泛化，还需要 full regression / holdout 证明，所以不能只凭代码审查宣布“没有过拟合”。

---

# 九、牛客近期高频追问题：按你的项目怎么答

下面这些问题来自近期 Agent 面经中反复出现的考察方向：项目链路、上下文与记忆、Agent 框架选型、工具异常、评测体系、长对话治理。回答已经按当前项目真实实现改写，不是通用模板。

## Q41：上下文窗口越来越长怎么办？

先分：

```text
History
State
Long-term Memory
```

不能所有东西全量注入。

当前项目 Chat History 本身限制最近消息；Canonical State 用结构化 WM，不需要通过重复历史文本带回；Decision Facts 通过 session pointer 按需读取。

工业系统还会做：

```text
trim recent messages
rolling summary
按需 memory retrieval
token budget
source / timestamp / permission filtering
```

长上下文不是越多越好，会增加延迟、成本和注意力污染。

---

## Q42：短期记忆和长期记忆是不是每轮都注入？

不是。

当前 Working Memory 属于 thread/task 热状态，本轮需要哪些就 projection 哪些；Historical Decision Fact 只有用户追问时按 sessionId 查询；未来如果做跨会话长期用户偏好，也应该按相关性和冲突规则召回，不是全量塞 Prompt。

---

## Q43：Memory 冲突怎么处理？

当前项目先做来源层级：

```text
USER_EXPLICIT
SYSTEM_DEFAULT
DERIVED
```

用户当前明确表达通常应该覆盖系统派生值；旧状态缺 provenance 时不假装确定。

工业级还会补：

```text
timestamp
confidence
sourceEventId
expiration
conflict state
```

你的项目目前没有做到这些，不要吹。

---

## Q44：工具返回错误 / 空数据怎么办？

首先区分：

```text
业务上确实无结果
vs
外部依赖失败
vs
输入状态不完整
```

Location 里就是典型例子：行政 identity 未确认不能直接当“商户0条”，要先 clarification；外部 Provider 失败应安全降级 NOT_FOUND，不允许 500 或猜 identity。

这体现的是：

> 工具失败处理不能只看异常类型，还要维护业务语义边界。

---

## Q45：为什么不用一个大模型把 Routing、Memory、Tool 全做掉？

因为不是所有问题都是 open-world semantic problem。

```text
用户想吃辣 / 安静
→ LLM 擅长

OCC version compare
Candidate invalidation
Administrative identity
Historical ordinal resolve
→ deterministic code 更合适
```

模型负责不确定性高的语言理解；业务 invariant 由代码负责。

---

## Q46：Workflow 和 Agent 怎么选？

如果步骤和边界明确：

```text
Rewrite → Route → Reduce → Guard → Execute
```

更像 Workflow / Pipeline。

开放式工具选择和语义推理可以 Agentic。

你的项目本身就是 Hybrid：不是为了名字把所有步骤都交给 LLM。

---

## Q47：你的 Agent 怎么避免状态幻觉？

“状态幻觉”最核心不是 Prompt 再强调一句，而是让 LLM 不拥有最终 business truth authority。

例如：

```text
行政区 → Resolver authority
Constraint 来源 → constraintSources
历史推荐 → persisted DecisionResponse
Reference → ResolvedShopReference
当前条件 → active Task.criteria
```

LLM 可以解释和表达，但不能无依据改写这些事实。

---

## Q48：为什么不把全部 Memory 放 Redis？

存储介质不是 Memory Architecture。

Redis / MySQL 只是“存在哪里”。真正需要回答：

```text
存什么
Scope 是什么
谁能写
什么时候失效
如何版本化
来源是什么
冲突怎么办
如何投影给模型
```

你的核心不是“用了 Redis”，而是 canonical state 与 history 的职责拆分。

---

## Q49：怎么测试 Agent？只看最终回答吗？

不能。

需要分层：

```text
Unit
→ reducer / resolver / reference / policy

Targeted E2E
→ 某个真实 badcase 的行为链

Full Regression
→ route/tool/state/final 等 contract

Holdout
→ 检查是否只对开发集过拟合
```

Agent 过程错误可能暂时生成“看起来对”的答案，所以要测 trajectory / state，而不是只做文本 Exact Match。

---

## Q50：如果面试官问“你最大的工程收获是什么”？

建议回答：

> 我最大的收获不是学会了某个 Agent 框架，而是意识到多轮 Agent 本质上也是状态系统。模型擅长语言理解，但一旦业务进入多轮、并发、历史引用、恢复和可解释性，必须明确哪些是 canonical state，哪些是历史事实，哪些只是本轮 context，以及谁拥有最终 authority。项目里很多 Bug 都来自这几个边界混在一起，我后来通过 Evaluation 把这些边界逐步拆开。

---

# 十、最容易被追问的架构 Trade-off

## Q51：Full Snapshot 会不会太重？

会，这是已知 trade-off。

当前规模小，Full Snapshot 换来了读写和调试简单。

长期可能演化：

```text
保留最近 N 个 Full Snapshot
老版本归档
周期 Full + Delta
Snapshot compression
```

LangGraph 官方也明确提到长 thread 全量 checkpoint 会产生存储增长，并提供 delta 型存储优化思路。你可以用它做业内对照，但不要说项目已经实现。

---

## Q52：为什么没有把 adcode 持久化进 WM？

当前 Retrieval Contract 只消费：

```text
province / city / district
```

adcode 现在主要服务 Resolver identity/hierarchy，没有明确下游业务消费者。

所以没有因为“未来可能有用”就扩大 durable schema。

如果未来数据库统一 adcode、跨数据源 join 或行政 rename，再升级为 canonical field。

---

## Q53：为什么没有做完整 Location FSM？

当前问题主要是 authority、维度和 projection 错，不是状态表达能力不足。

已有：

```text
EXPLICIT_TARGET
CURRENT_DEVICE
province/city/district/area
searchLocation
deviceLocation
```

足够表达业务。

所以选择修边界，而不是新造 LocationSaga / GeoWorkflow。

---

## Q54：为什么 `DECISION_CONTEXT_QUERY` 不更新 focusedShop？

因为它是 read query。

用户问“为什么第二家”只表示查询解释，不代表“我现在选中第二家”。

如果查询过程中偷偷改变 focus，就会让读操作产生隐藏副作用，同时 WM version 也会变化。

所以 strict read-only：

```text
不 reduce
不切 Task
不 invalidate
不 append batch
不更新 focus
不改 WM version
```

---

# 十一、当前明确的工程债和不能吹的地方

## Q55：当前还存在哪些问题？

### 1. Task Identity 仍偏启发式

不是通用 Task Manager。

### 2. Historical Task Reference 仍有 NLP 下沉债务

部分 Task 恢复仍有 StateService 字符串判断，理想边界应是 `TaskReferenceIntent → Resolver`。

### 3. Full Snapshot 会随历史 Batch 增长

没有完整 archive/TTL/compaction framework。

### 4. 不是 Durable Workflow Runtime

能恢复业务 State，不能恢复 Pipeline 任意 Node / Tool 的执行位置。

### 5. Provenance 还比较轻

没有 per-field timestamp/confidence/sourceEventId/expiry。

### 6. 行政 Registry 是 partial

Provider 是 coverage fallback，不是全国地图基础设施。

### 7. Compound Intent 仍有 Single Execution Action 的边界

TurnPlan 已把 mutation/execution 拆开，但不是任意复杂多动作规划器。

### 8. 最新 Holdout 尚未跑

不能声称当前 HEAD 在 holdout 上的最终成绩。

---

# 十二、面试红线：这些话不要说

不要说：

```text
我的 Memory 就是 Redis 聊天记录
```

不要说：

```text
我一开始就设计好了完整 Working Memory
```

不要说：

```text
我做了 Event Sourcing
```

不要说：

```text
用了 Task 就完全解决多轮状态
```

不要说：

```text
行政区都交给 LLM，模型识别得很准
```

不要说：

```text
本地词表只有一个候选，所以就是唯一行政区
```

不要说：

```text
Robustness 18/48，所以系统只成功 18 条
```

也不要反过来说：

```text
Robustness 基本都没问题，只是 Ground Truth 错
```

真实情况是两者都有：一部分 Dataset Contract 漂移，一部分真实债务仍在。

---

# 十三、建议背熟的 12 个“不变量”

```text
1. Chat History ≠ Canonical Business State

2. Current Candidate Projection ≠ Historical Recommendation Fact

3. Conversation 可以包含多个 Task

4. 同一 Turn 只能使用一个 authoritative WM Snapshot

5. 跨 Turn 并发靠 OCC；旧 Runtime Result 不能覆盖新 State

6. Reference 属于用户发话时刻的 Pre-Mutation Snapshot

7. Criteria Mutation ≠ Execution Action

8. Value ≠ Intent ≠ Provenance ≠ Projection

9. Administrative Level ≠ Administrative Identity

10. String Mention ≠ Entity Mention

11. Partial Dataset ≠ Complete World

12. Read Query ≠ Mutation
```

这 12 条比背一堆类名更重要。类名忘一点可以现场推，状态不变量讲不清就很容易被追问穿。

---

# 十四、业内方案对照：面试时怎么引用，不要乱吹

## OpenAI Agents SDK

官方 Session：

- 负责 session conversation history 的读取/追加；
- 可以使用自定义后端、SQLAlchemy/OpenAI Conversations 等；
- Context 还区分本地 runtime context 和 LLM-visible context。

你可以说：

> 这说明 Session/History 与业务 Context 是两个层次；我的项目也把 Chat History、Turn Context 和 Canonical WM 分开。

来源：

- https://openai.github.io/openai-agents-python/sessions/
- https://openai.github.io/openai-agents-python/context/
- https://openai.github.io/openai-agents-python/running_agents/

## LangGraph

官方 Persistence：

- thread-scoped state；
- checkpoint / StateSnapshot；
- history / time travel；
- memory / human-in-the-loop / fault tolerance；
- 长 thread 全量 checkpoint 也存在 storage growth trade-off。

你可以说：

> 我们的 versioned WM 和它在“thread state + historical snapshot”的思想上有相似性，但我们的 checkpoint 粒度是业务 State，不是通用 graph super-step，因此能力边界更小。

来源：

- https://docs.langchain.com/oss/python/langgraph/persistence
- https://docs.langchain.com/oss/python/langgraph/add-memory
- https://docs.langchain.com/oss/python/concepts/memory

## Microsoft Agent Framework / Semantic Kernel

官方 AgentThread / AgentSession 用于跨 invocation 管理 conversation state；Workflow/Orchestration 侧提供不同编排与 checkpoint/resume 思路。

你可以说：

> 成熟框架通常提供 session/thread/workflow 基础设施，但领域状态仍需业务自己定义；框架不会自动知道“预算100”和“和女朋友吃饭推导约会”的 provenance 区别。

来源：

- https://learn.microsoft.com/en-us/semantic-kernel/frameworks/agent/agent-architecture
- https://learn.microsoft.com/en-us/agent-framework/agents/conversations/

---

# 十五、牛客近期面经对照：为什么这些题要重点准备

近期牛客 Agent 面经反复出现的方向包括：

```text
项目整体链路
State / Context / Memory 区分
上下文窗口和记忆污染
长短期记忆
为什么不用 LangGraph / 为什么自研
工具失败与容错
Agent 如何评测
如何避免过拟合
Workflow vs Agent
RAG / Agent 工程化稳定性
```

其中与你当前项目最相关的帖子：

- 「agent面经-上下文工程与记忆面试问答」：直接问 State、Context、Memory 区别、记忆分层和上下文污染。
  https://www.nowcoder.com/discuss/916877706187276288
- 「AI岗位高频面试题整理：Agent记忆机制/上下文管理」：关注记忆冲突、多轮对话、版本和来源。
  https://www.nowcoder.com/discuss/907602041831256064
- 「AI Agent 面试 Top50 必刷题」：把 Agent 上下文管理、记忆机制、评测列为高频方向。
  https://www.nowcoder.com/discuss/886328246717911040
- 「百度 Agent 面经」：项目深挖会追到链路、框架和记忆，而不是只问框架名。
  https://www.nowcoder.com/discuss/880841659733311488
- 「字节 Agent 开发岗面经」：常问 Agent 整体架构、Query Rewrite、项目核心能力和工程有效性。
  https://www.nowcoder.com/discuss/922659486983036928

看这些面经时不要去背别人答案。你的优势是这些题在项目里都有真实 Bad Case、真实演进和代码边界，面试时优先讲自己的因果链。

---

# 十六、最后一页：面试官怎么追，你怎么接

如果面试官问：

```text
为什么做 Working Memory？
```

你答：

```text
History 无法作为 canonical business truth
→ 多轮候选/方案/清除/并发都不稳定
→ 通过 Evaluation 演化出 structured WM
```

他追：

```text
为什么不用 LangGraph？
```

你答：

```text
当前流程高度确定 + Java 项目
→ 自己维护业务 State 更直接
→ LangGraph 擅长通用 graph/checkpoint/durable workflow
→ 当前没有跨小时复杂执行需求
```

他追：

```text
自己做并发安全吗？
```

你接：

```text
same-turn coherent snapshot
+
version OCC
+
stale runtime result guard
```

他追：

```text
Memory 会不会越来越大？
```

你接：

```text
会，Full Snapshot 是当前小规模 trade-off
→ evidence 不复制进 WM
→ 后续可 periodic full + delta / archive / compaction
```

他追：

```text
怎么证明没过拟合？
```

你接：

```text
生产代码禁 Case hardcode
→ targeted regression
→ 阶段 full robustness/v1
→ 最终 holdout
→ 当前 holdout 尚未跑，所以不夸泛化结论
```

他追：

```text
为什么你这个项目不是普通聊天机器人？
```

你接：

```text
因为核心已经不是把历史拼进 Prompt，
而是有独立 Canonical State、Task、Reference、Policy、Tool、Version、Evaluation，
模型只是系统中的一个语义组件。
```

---

## 当前最准确的一句话总结

> **我做的不是一个“记住聊天历史”的 Memory，而是一套面向多轮消费决策的业务状态系统：当前事实由 Task-scoped Working Memory 管理，历史推荐由 RecommendationBatch + DecisionSession 保留，状态来源通过 Provenance 区分，行政实体由独立 Resolver 掌握 authority，Reference 和 Context Query 只读投影历史事实；状态更新再用同轮 Snapshot 一致性、OCC 和 Evaluation 保证不会因为多轮、并发或模型重解释而失真。**
