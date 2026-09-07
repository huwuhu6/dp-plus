# AI 消费决策 Agent——面试主线总纲

> 当前唯一面试主资料。Working Memory、Task、RecommendationBatch、Reference、Location、Turn Semantics、Decision Context、No-result Recovery、Fact Tool、Evaluation 后续统一维护在本文，不再拆新的“主线补充”文档。
>
> 当前 Code Truth：main `b3f595f0f4d182df7c41d6ba5fc15ea3c1513c39`
>
> 当前归档评测：Robustness Run141、Conversation-v1 Run142、Holdout Run143；本轮未修改 Dataset Ground Truth。
>
> 本文统一使用同一种讲法：**真实现象 → 第一版判断 → 为什么失败 → 根因 → 方案取舍 → 最终设计 → 代码职责 → 验证 → 新暴露边界 → 面试追问。**

---

# 一、项目到底在解决什么

## 1. 30 秒版本

我做的是一个本地生活餐饮消费决策 Agent，重点不是单轮“让大模型推荐餐厅”，而是多轮决策状态管理。用户会改地点、预算、菜系，会同时考虑多套方案，会问“最开始第二家”“这个日本料理重口吗”“我什么时候说过安静”“那附近有啥”。如果只靠 Chat History 和模型每轮重解释，状态会串、历史引用会漂移、事实问题会偷偷改搜索条件。

项目因此逐步演化出：

```text
Task-scoped Working Memory
RecommendationBatch
Reference Resolver
TurnPlan
same-turn snapshot
OCC / stale-result guard
Location Authority
Decision Context Query
Canonical Turn Semantics
No-result Recovery
Descriptive Reference
Fact → Tool Contract
Evaluation
```

一句话：

> 我做的核心不是“让模型更会聊”，而是把多轮 Agent 中容易漂移的业务语义逐步收敛成有唯一 authority、可持久化、可回放、可评测的状态系统。

---

## 2. 2 分钟版本

聊天入口是一条显式 Pipeline：

```text
Bootstrap
→ Context Rewrite / Enrichment
→ Intent Routing
→ Criteria Reduction
→ Policy Guard
→ Execution
```

最早系统没有 canonical Working Memory，状态散在 History、当前请求 Context、candidatePool、focusedShop 等位置。真实对话和 Evaluation 持续暴露问题：

```text
换一批后历史顺序丢失
→ RecommendationBatch

A→B→A 方案串状态
→ Task Scope

同一请求重新 load 旧状态
→ same-turn authoritative snapshot

并发旧请求覆盖新状态
→ OCC + stale runtime result guard

一句话同时改条件又问第二家
→ TurnPlan

GPS、行政区、POI、命名地点混用
→ Location Contract + Resolver Authority

“为什么推荐”“我说过安静吗”靠 History 重猜
→ Decision Context Query + Provenance

“这个日料咋样”被写成 cuisine=日料
→ Canonical Turn Semantics

无结果后“那附近有啥”无法恢复
→ BROADEN_FOOD_SCOPE + Failure Explanation

Relaxation 只改 DecisionSession、WM 仍是旧条件
→ Command Projection 回 canonical WM

“这个日本料理重口吗”绑定 focused 东北菜
→ Descriptive Candidate Reference

“重口吗”走 detail / no tool
→ Shop Fact Type → Evidence Tool Contract
```

最终形成的不是“所有事情都交给 LLM”，而是：

```text
开放语义理解 → LLM
实体/引用 Grounding → Resolver
状态写权限 → Turn Semantics
业务状态 → Deterministic Reducer
命令合法性 → FSM
外部事实 → Tool
历史事实 → DecisionSession / RecommendationBatch
```

---

# 二、先把几个核心概念分清

## 1. History、Context、Working Memory、DecisionSession

```text
Chat History
= 用户和系统说过什么

ChatProcessingContext
= 当前 Turn 的 runtime 数据

ConversationWorkingMemory
= 系统当前相信的 canonical business state

DecisionSession
= 某一次决策执行的输入、结果和历史事实

RecommendationBatch
= 某一轮推荐候选的身份、顺序与轻量 reference metadata
```

关键：

> 历史里“说过”不等于当前仍生效；DecisionSession 当时如何执行，也不等于当前 Working Memory 现在是什么。

---

## 2. Canonical Working Memory 和 Canonical Turn Semantics

这是当前项目最重要的一组区分：

```text
Canonical Working Memory
回答：系统现在相信什么？

Canonical Turn Semantics
回答：用户这一轮允许系统做什么？
```

只有 WM，没有 Turn Semantics，仍可能发生：

```text
用户问“这个日料咋样”
→ Extractor 抽到 cuisine=日料
→ 错误写进 WM
```

所以“状态有唯一真相”不够，**写状态前的语义权限也必须有唯一真相。**

---

# 三、演进循环 1：candidatePool → RecommendationBatch

## 真实现象

```text
第一次推荐 A/B/C
→ 用户“换一批”
→ 第二次推荐 D/E/F
```

系统既要知道当前操作的是 D/E/F，又不能忘记 A/B/C 已展示。

## 第一版

```text
candidatePool = 当前候选
shownPool = 历史看过的商户
```

## 为什么失败

用户问：

```text
最开始第二家怎么样？
```

`shownPool=[A,B,C,D,E,F]` 只能回答“看过谁”，不能回答“在哪一批、第几个”。

## 根因

丢失了历史推荐的 batch boundary 和 ordinal。

## 最终方案

```text
RecommendationBatch
├─ decisionSessionId
└─ candidates[]
```

当前 candidate projection 来自 latest batch；历史 shown union 来自所有 batch。

当前 CandidateRef 不再只保存 id/name/price/distance，还补了轻量：

```text
cuisine
referenceTags[]
```

原因是后续真实对话又出现：

```text
“日本料理那个”
“那个烧烤”
```

如果 Batch 只有 shopId/name，就无法稳定做描述性 grounding。

但仍然不把评论、evidence、matchedReasons 长文本复制进 WM。完整历史证据继续留在 `AiDecisionSession.resultJson`。

## 面试结论

> Historical identity 需要足够的 reference metadata，但不能为了方便引用把完整历史 payload 复制进每个 Working Memory snapshot。

---

# 四、演进循环 2：Flat WM → Task Scope

## 真实现象

```text
福州火锅100
→ 杭州西湖日料300
→ 还是最开始那套
```

Flat WM 只有一套 criteria，很容易恢复成：

```text
福州 + 火锅 + 西湖 + 300
```

## 第一版

继续在一套 criteria 上 replace / clear。

## 为什么失败

这不是字段更新问题，而是用户同时维护多套 Decision Task。

## 最终方案

```text
ConversationWorkingMemory
└─ tasks[]
   ├─ Task A: 福州 / 火锅 / 100
   └─ Task B: 杭州 / 日料 / 300
```

恢复 A 是 re-activate A，不是拿 B 再拼一遍。

## 为什么预算不做 Task Identity

否则：

```text
福州火锅100
福州火锅80
福州火锅120
```

会碎成三个 Task。预算通常是 refinement，不是任务身份。

## 当前边界

Task Identity 仍是餐饮业务启发式，不是通用 Task Manager。

---

# 五、演进循环 3：same-turn Snapshot 和 OCC

## 真实问题

Task V2 单测正确，但 E2E 仍失败。

定位发现：

```text
transitionTask()
→ 修改当前请求里的 WM

reduceCriteria()
→ 又从 DB load 同 version 的旧 WM
```

新 Task 被同一请求中的旧 Snapshot 覆盖。

## 根因

一个 Turn 内出现两个世界视图。

## 不变量

```text
一个 Turn
只能有一个 authoritative Working Memory view
```

```text
bootstrap snapshot
→ transition
→ merge
→ reduce
→ persist
```

都基于同一 request-scoped snapshot。

## 为什么还要 OCC

same-turn snapshot 解决一个请求内部；OCC 解决多个请求之间：

```text
expectedVersion != latestVersion
→ VersionConflictException
```

一句话：

> 同一 Turn 看同一个世界；并发 Turn 不允许旧世界覆盖新世界。

---

# 六、演进循环 4：OCC → stale runtime result guard

## 场景

```text
Turn A 在 WM v20 启动慢 Tool
Turn B 把状态更新到 v21
Turn A 5 秒后返回
```

即使持久化层有 OCC，A 的 Tool Result 仍然是基于旧世界算出来的。

## 最终方案

`AgentSessionContext` 保存：

```text
baseWorkingMemoryVersion
```

回写前读取 latest：

```text
base != latest
→ STALE_RUNTIME_RESULT
→ reject
```

## 为什么用 Full Snapshot，不做 Event Sourcing

当前状态规模可控，Full Snapshot 的优势是读、恢复、debug 都简单。事件用于审计，不靠 event replay 重建所有业务状态。

长期代价是长会话存储增长，未来才考虑 periodic full + delta / compaction。

---

# 七、演进循环 5：Reference + TurnPlan

## 问题 A：ordinal reference

```text
第一家
第二家
最开始第二家
刚才那家
```

如果每个 Handler 自己理解，会出现多套 ordinal 语义。

最终：

```text
Natural Language
→ ReferenceIntent
→ BatchAwareReferenceResolver
→ ResolvedShopReference
```

## 问题 B：一句话有两个动作

```text
第一家太贵了，第二家有插座吗？
```

单 Action 无法同时表达：

```text
Criteria Mutation
+
Business Follow-up
```

所以：

```text
TurnPlan
├─ CriteriaIntent: NONE / APPLY_DELTA
└─ ExecutionAction
```

## 为什么 reference 必须 pre-mutation resolve

“第二家”属于用户发话时看到的 batch。如果先 mutation 导致 candidate invalidation，再解析 ordinal，reference 会漂移。

```text
Reference belongs to pre-mutation snapshot
```

---

# 八、演进循环 6：Location Contract 一步步长出来

## 1. Named Location 和 Browser GPS 混用

用户明确说“重庆”，系统不能继续拿福州 GPS。

因此区分：

```text
CURRENT_DEVICE
EXPLICIT_TARGET
```

设备 GPS 只有在用户表达“我附近/当前位置”时才是 search anchor。

---

## 2. Province / City / District / Area 混用

`福建省` 不能放 targetCity；“师大”也不能放 targetDistrict。

最终：

```text
targetProvince
targetCity
targetDistrict
targetArea
```

`targetArea` 用于 POI/商圈/地标。

---

## 3. 行政实体不能完全交给 LLM

真实问题：

```text
鼓楼有什么吃的
```

模型可能不稳定抽出行政身份。

行政区是 closed-world identity，所以引入：

```text
AdministrativeRegionResolver
→ local registry
→ provider fallback
```

模型只提供 hint，Resolver 才拥有 identity authority。

---

## 4. Partial Dataset ≠ Complete World

本地 registry 只有一个“鼓楼区”，不代表全国只有一个。

因此裸区县无 parent 时，不能因为本地唯一就直接执行；可信 parent/provider 不足时应澄清。

---

## 5. Administrative Authority Leak

真实“连江”问题：

```text
Resolver = NOT_FOUND
LLM targetDistrict=连江县
旧 merge 对 NOT_FOUND 什么都不做
→ LLM 字段仍偷渡进 canonical state
```

最终改成：

```text
LLM admin hint
→ raw-query grounding
→ resolver/provider validate
→ verified identity
→ canonical state
```

失败则清除未验证 admin field。

结论：

> 有 Resolver 类不代表它真的拥有 authority；旧旁路必须全部失去写权。

---

## 6. String Mention ≠ Entity Mention

```text
福州大学附近
```

包含“福州”，不等于用户显式设置“福州市”。Substring grounding 会把 POI 错洗成行政区。

所以 admin hint 必须经过 raw-query grounding 和 POI boundary。

---

## 7. POI Mention ≠ Executable Search Anchor

真实问题：

```text
师大附近有没有啥好吃的？
```

旧系统写了 `targetArea=师大`，但没有真正 resolve POI；后面又复用旧 GPS 搜索。

最终：

```text
Administrative scope
→ AdministrativeRegionResolver

POI / Landmark / Business Area
→ LocationResolutionProvider

CURRENT_DEVICE
→ Browser GPS
```

POI 未解析成功必须 clarification，不能 silent fallback 到旧 GPS。

---

# 九、演进循环 7：地点简称和校区——“农大附近”

这是前端真实使用暴露的新问题。

## 真实对话

```text
我想去农大附近吃饭
```

用户不可能每次都输入完整正式名，更不会每次写：

```text
福建农林大学某某校区
```

现实里会大量出现：

```text
农大
福大
师大
万达
大学城
```

甚至同一学校有不同校区。

## 为什么之前的安全 Contract 仍不够

以前我们做到的是：

```text
POI 未 resolve
→ 不偷用 GPS
→ clarification
```

安全了，但用户体验过于保守。

## 根因

原接口只有：

```text
resolve(String placeText)
```

没有上下文：

```text
设备坐标
当前 city/district
当前 named location
```

所以“农大”只能孤立解析。

## 当前方案

新增：

```text
LocationResolutionRequest
LocationResolutionContext
```

携带：

```text
rawText
device lat/lng
active province/city/district
currentNamedLocation
```

当前实际 MCP 只确认有 `maps_geo`，因此没有臆造 `maps_text_search/maps_around_search`。实现会把 active city 作为 query context，并在 provider 返回多个候选时用 device distance 排序。

同时 `ResolvedLocationCandidate` / `ConversationLocationSlot` 增加轻量：

```text
poiId
canonicalName
```

避免最后只剩一组坐标，不知道解析的是哪个具体 POI/校区。

## GPS 的职责边界

```text
GPS 可以做 Named POI 的 disambiguation prior
≠
GPS 可以替代 Named POI 成为最终 search anchor
```

如果 provider 无法权威解析简称，仍然澄清，不能因为“用户大概在福州”就直接把“农大”硬编码成某所大学。

## 当前限制必须诚实说

当前环境 provider 不可用时，“农大/福大”只能安全进入 `LOCATION_RESOLUTION`，还不能宣称“已经自动解析成具体学校/校区”。如果后续 provider 增加 POI keyword/around search，当前 Request/Context contract 已经能接入，不需要再改 Working Memory 主模型。

---

# 十、演进循环 8：Decision Context / Provenance

## 真实问题

```text
为什么推荐第一家？
我什么时候说过安静？
你的过滤条件是什么？
你刚才查的是哪里？
```

这不是新推荐，而是在读取已有决策事实。

## 第一版

让 GENERAL_CHAT 从 History 自由回答。

## 为什么失败

History 只能告诉“说过什么”，不能告诉当前来源。例如 `安静` 可能来自用户显式，也可能从“适合聊天”派生。

## 最终方案

```text
DECISION_CONTEXT_QUERY
```

QueryType：

```text
WHY_RECOMMENDED
CONSTRAINT_PROVENANCE
CURRENT_CRITERIA
EXECUTED_SEARCH_SCOPE
```

严格 read-only，不 reduce、不切 Task、不 invalidate、不改 focus、不增加 WM version。

## element-level provenance

不能写：

```text
preferences → USER_EXPLICIT
```

因为一个 list 内不同元素来源不同。

使用：

```text
preference:安静 → DERIVED
preference:约会 → USER_EXPLICIT
```

## 历史解释为什么读 DecisionSession

历史第一批推荐是在旧 budget/location 下产生的。问“为什么最开始第一家被推荐”时必须：

```text
Resolved Reference
→ RecommendationBatch.decisionSessionId
→ AiDecisionSession.resultJson
```

不能拿当前 WM 重解释历史，否则发生 Temporal Leakage。

---

# 十一、演进循环 9：Canonical Turn Semantics——Mention ≠ Mutation

## 真实 Bad Case

```text
这个日料咋样？
```

用户只是在问店，但旧 Extractor 抽到：

```text
cuisine=日料
```

旧逻辑再用：

```text
hasMutation(criteriaDelta)
```

于是把“日料”写成搜索条件。

另一个 Case：

```text
我附近呢？除了东北菜应该都OK
```

旧 Context Rewrite 看到“我附近”提前返回，后半句排除条件直接丢失。

## 第一反应为什么不够

如果只补 cuisine provenance 或再加几个 contains，本质仍是多个组件各自重新解释同一句话。

## 根因

```text
Canonical State 有了
Canonical Turn Semantics 没有
```

## 最终方案

request-scoped：

```text
TurnCommandSet
TurnCommand
TurnUnderstandingService
```

它不保存长期业务事实，只统一：

```text
用户这一轮允许系统做什么？
```

## 最关键职责

```text
TurnUnderstanding
→ 决定“能不能改”

ConstraintExtractor
→ 允许后决定“具体改什么”

Merger / ConversationStateService
→ 决定“怎么落状态”
```

核心不变量：

```text
Mention ≠ Mutation
Fact Question ≠ State Update
Original Turn ≠ Rewritten Query
```

Context Rewrite 因此降级为 reference/ellipsis enrichment，不再替代 original message 成为整轮业务真相。

---

# 十二、演进循环 10：第一版 Turn Semantics 又为什么返工

## full regression 暴露

`selectAction()` 已经使用新的 TurnUnderstanding，但 `route()` 仍然：

```text
isCompoundMutationFollowUp()
→ ensureCriteriaDelta()
→ hasMutation(criteriaDelta)
```

形成双 Mutation Authority。

真实结果：

```text
第一家那个日本料理环境怎么样？
```

仍可能因为抽到 cuisine=日料而 mutation。

## 最终修复

```text
route()
→ hasStructuredReferenceMutation()
→ TurnUnderstandingService

selectAction()
→ 同一个 authority
→ 只有 YES 才 ensureCriteriaDelta()
```

旧 `hasMutation(DecisionConstraints)` 删除。

缺少 TurnUnderstandingService 时 reference mutation fail-closed。

面试一句话：

> Extractor 可以提供 Delta，但不能因为“抽到了字段”就获得 State Write Permission。

---

# 十三、演进循环 11：negative cuisine 为什么要改 schema

```text
除了东北菜都可以
```

不是：

```text
cuisine = 某个正向值
```

而是：

```text
ANY cuisine EXCEPT 东北菜
```

所以新增：

```text
excludedCuisines[]
```

它是 durable hard constraint，进入 MySQL hard filter，同时属于 candidate universe；增删排除菜系会 invalidate 当前 candidate/focus，但不删除历史 RecommendationBatch。

负向菜系 fallback 复用 `CuisineCanonicalizer`，而不是分别给日料、火锅、烧烤写平行规则。

---

# 十四、演进循环 12：WAITING_RELAXATION——状态有了，为什么用户还是觉得系统在“吹牛”

## 真实对话

```text
附近兰州拉面
→ GPS
→ 3/5km 都 0 结果
→ 没有兰州拉面吗
→ 什么意思
→ 我就要吃兰州拉面
→ 仍然 0
→ 无敌了，那附近有啥
```

旧系统最后把“那附近有啥”判成 GENERAL_CHAT。

同时首次无结果只说“当前条件没有商户，可以扩大范围”，用户根本不知道：

```text
搜了哪里？
多远？
还保留兰州拉面吗？
预算还在吗？
```

## 为什么只修 Route 不够

就算把“附近有啥”强行 START_DECISION，旧 WM 里仍有：

```text
keyword=兰州拉面
cuisine=面食
```

重新搜仍然可能是 0。

## 根因

缺的是两个 Contract：

```text
Recovery Command Contract
+
Failure Explanation Contract
```

## BROADEN_FOOD_SCOPE

只在：

```text
WAITING_RELAXATION
+
已有具体 food target
+
用户表达泛化
```

时合法。

语义：

```text
clear keyword/cuisine
保留 location/GPS
保留 radius
保留 budget
保留其他显式 preferences
保留历史 RecommendationBatch
→ retry
```

普通非暂停态“附近有啥”不会误触发该 recovery command。

## 强/弱泛化

```text
强放弃：随便 / 都行 / 不一定 / 不限 / 其他……
弱泛化：啥 / 什么
```

弱泛化只有在用户没有再次提到当前 food target 时才 broadening。

还因此补了 `containsNonBlankTarget()`，避免空字符串导致 `text.contains("") == true` 把恢复错误拦住。

---

# 十五、演进循环 13：Relaxation 又暴露 DecisionSession / WM 双真相

## 问题

`continueDecision()` 直接修改 `DecisionSession.constraints`：

```text
EXPAND_RADIUS
INCREASE_BUDGET
RELAX_CUISINE
BROADEN_FOOD_SCOPE
...
```

但 `snapshotDecision()` 按设计不覆盖 `activeTask.criteria`。

于是可能：

```text
DecisionSession：已经泛搜
WM：仍然兰州拉面/面食
```

## 为什么不能让 snapshotDecision 全量反写

因为已经建立过不变量：

```text
Execution Result ≠ Canonical State Writer
```

全量覆盖会把执行层临时值重新偷渡进 WM。

## 最终方案

```text
ConversationStateService.applyDecisionRelaxationCommand()
```

只把已经被 FSM 验证的 command 按白名单投影到 canonical criteria：

```text
BROADEN_FOOD_SCOPE → clear keyword/cuisine
RELAX_CUISINE → clear cuisine
EXPAND_RADIUS → sync radius
INCREASE_BUDGET → sync budget
RELAX_QUIET / ALLOW_QUEUE / RELAX_LIGHT_TASTE → remove 对应 preference/source
```

每次放宽 invalidate 当前 candidate/focus，历史 batch 保留。

最终职责：

```text
Turn Semantics
→ 用户发了什么 command

Decision FSM
→ command 现在是否合法

Canonical WM Command Projection
→ command 如何改变当前业务事实

DecisionSession
→ 记录本次 execution fact
```

---

# 十六、演进循环 14：Failure Explanation 也要单一事实源

首次 WAITING 和后续“什么意思？”如果各自拼一套文案，会出现：

```text
第一次说 5km
第二次说 7km
```

或者条件列表漂移。

因此新增：

```text
DecisionFailureExplanationFormatter
```

首次进入 WAITING 和后续 `EXPLAIN_SUSPENDED_DECISION` 共用同一 formatter，从 DecisionResponse / constraints / RelaxationInfo / options 生成：

```text
搜索范围与半径
keyword/cuisine/budget/preferences
0 结果
自动扩大历史
当前可执行下一步
```

这不是纯 UX 美化，而是 Failure Explainability。

---

# 十七、演进循环 15：“这个日本料理重口吗？”——描述性引用为什么会绑错店

这是前端真实聊天暴露的关键问题。

## 场景

推荐三家：

```text
A：东北菜（focused）
B：日本料理
C：烧烤
```

用户问：

```text
这个日本料理重口吗？
```

旧系统解析：

```text
“这个” → FOCUSED
→ A 东北菜
```

然后回答：“东北菜不是日本料理”。

## RecommendationBatch 有没有坏

没有。B 仍在正确 Batch 中，用户下一轮“我问的是那个日本料理”就能解析到 B。

所以这不是 candidate 丢失，而是 Reference Contract 不够。

## 旧架构为什么必错

旧 fast path：

```text
这个 / 这家 / 那个
→ FOCUSED
```

`BatchAwareReferenceResolver` 一旦收到 FOCUSED，就只看 focusedShopId，不再利用“日本料理”这个限定词。

## 根因

```text
Deictic Reference
和
Descriptive Qualifier
```

被压成了一个 FOCUSED 标签。

## 最终方案

`ReferenceIntent` 增加：

```text
qualifier
deictic
```

规则和模型重叠时，不再只保留 mutationAnchor，而会用模型更完整 surface/qualifier enrich rule intent。

解析优先级：

```text
Explicit Ordinal
>
Unique Descriptive Qualifier
>
Pure Deictic Focused Fallback
```

例如：

```text
“这个”
→ qualifier=null
→ focused

“这个日本料理”
→ qualifier=日本料理
→ latest batch 内匹配

“那个烧烤”
→ qualifier=烧烤
→ batch 内匹配
```

唯一匹配才 resolve；多个匹配 → AMBIGUOUS；有 qualifier 但无匹配 → BLOCKED/clarify，绝不能回退 focused。

## Working Memory 为什么需要补 reference metadata

`RecommendationCandidateRef` 原本只有 id/name/price/distance，不足以解释“日本料理那个”。因此只增加：

```text
cuisine
referenceTags[]
```

这些是轻量、稳定、用户可见的 reference descriptor，不复制 evidence 长文本。

## 当前进一步边界

qualifier matching 当前仍是轻量 deterministic matching。像“日本料理”与 canonical “日料”如果店名本身不含 qualifier，未来可以进一步复用 `CuisineCanonicalizer` 做 descriptor canonicalization；今晚归档版本不把它包装成通用实体链接系统。

---

# 十八、演进循环 16：“重口吗？”为什么不能只查 get_shop_detail

即使 Reference 正确绑定 B，日本料理“重口吗”仍有独立问题。

## 旧问题

Planner 可能：

```text
get_shop_detail
```

或者：

```text
NO_TOOL_CALL
```

但 detail 只能回答地址、人均、营业等静态事实，不能证明“重口/清淡/辣/油不油”。

## 根因

缺少：

```text
Shop Fact Semantics
→ Tool Contract
```

## 最终方案

新增 request-scoped：

```text
ShopFactQueryType
├─ STATIC_DETAIL
├─ EVIDENCE
└─ VOUCHER
```

主观体验统一归 EVIDENCE：

```text
口味强弱
辣/咸/油腻/清淡
环境
服务
排队
场景适配
评价/口碑
```

Planner prompt 和 deterministic fallback 两侧都约束：

```text
EVIDENCE → search_shop_evidence
```

不能只相信 Planner 偶然选对。

## Evidence no-match

`SearchShopEvidenceTool` 先找 topic-specific evidence；若没有明确主题证据：

```text
topicMatched=false
→ 返回有限 general evidence
→ 明确说“现有评价没有明确提到该主题，暂时不能确定”
```

不能拿一般评价伪装成“重口/不重口”的结论。

## 当前边界

Fact taxonomy 目前仍是小型业务分类器，不是通用 QA ontology；但它比“Planner 自由猜工具”稳定，因为主观事实查询已经有确定性 fallback contract。

---

# 十九、当前数据模型和 Authority Map

## ConversationWorkingMemory

```text
schemaVersion
activeDecisionSessionId / lastDecisionSessionId
deviceLocation
pendingLocationCandidates
activeTaskId
tasks[]
focusedShopId / focusedShopName
dialogPhase
lastPolicyAction / lastPolicyReason
```

## DecisionTaskState

```text
taskId
title
criteria
constraintSources
searchLocation
recommendationBatches[]
```

## ConversationLocationSlot

```text
status
poiId
canonicalName
latitude / longitude
province / city / district
source
capturedAt / expiresAt
```

## RecommendationCandidateRef

```text
shopId
shopName
pricePerPerson
distanceKm
cuisine
referenceTags[]
```

## TurnCommandSet

request-scoped，不 durable：

```text
本轮语义权限
mutationRequested
referenceOnly
factQueryType
commands[]
```

## Authority Map

```text
当前 criteria
→ ConversationWorkingMemory

Task identity
→ ConversationStateService / transitionTask

reference identity
→ ReferenceIntent + BatchAwareReferenceResolver

行政 identity
→ AdministrativeRegionResolver

POI identity
→ LocationResolutionProvider

mutation permission
→ TurnUnderstandingService

command legality
→ DecisionTransitionService / FSM

历史执行事实
→ DecisionSession

历史推荐顺序
→ RecommendationBatch

主观商户事实
→ search_shop_evidence
```

---

# 二十、Evaluation：为什么测试数据漂亮但前端还会出问题

这是这轮真实聊天最值得讲的复盘。

## 1. Evaluation 能发现什么

结构化 Dataset 很适合稳定验证：

```text
Route
Tool
Final Status
Locality
Working Memory projection
Context Rewrite
历史引用
Task 切换
```

它成功推动了 Task、Batch、OCC、Location、Turn Semantics 等多轮演进。

## 2. 为什么仍会漏掉真实前端问题

Dataset 通常写得比真实用户“规范”：

```text
第一家评价如何
福州大学附近
推荐附近日料
```

真实用户更常说：

```text
农大那边
这个日本料理重口吗
那附近有啥
搞什么
这家呢
```

这些问题往往不是 Route 单点，而是：

```text
ellipsis
简称
deictic + qualifier
paused-state recovery
fact-type ambiguity
```

所以不能只看 Complete 数字。

## 3. 最终测试层次

```text
Unit / targeted
→ 验证局部不变量

HTTP directed E2E
→ 验证真实主链 wiring

Robustness / conversation-v1
→ 回归稳定 Contract

Holdout
→ 检查对固定 Dataset 的过拟合

人工自然语言 smoke
→ 专门覆盖口语、省略、简称、前端真实表达
```

本轮第一次把 `holdout + 人工 HTTP smoke` 一起作为归档验收，而不是只看测试用例。

---

# 二十一、当前归档评测

## Run141 — conversation-robustness-v1

```text
Cases: 48
Complete: 20/48
Route: 43/48
Tool: 47/48
Final Status: 36/48
Locality: 48/48
```

## Run142 — conversation-v1

```text
Cases: 40
Complete: 29/40
Route: 38/40
Tool: 33/40
Final Status: 40/40
Locality: 40/40
```

## Run143 — conversation-holdout-v1

```text
Cases: 16
Complete: 7/16
Route: 13/16
Tool: 14/16
Final Status: 12/16
Locality: 16/16
```

不要说“Complete 只有 20，所以系统只有 20 条能用”。Complete 是多个 contract 的 AND；必须拆 Route / Tool / Final / WM / Locality 看失败聚类。

同样不能把所有失败都推给旧 Ground Truth。当前仍有真实债务：行政区澄清、部分 Route/Tool 语义差异、Context Rewrite、通用 compound semantics。

---

# 二十二、和业内方案怎么对照

## OpenAI Agents SDK

Session 更接近 conversation items 的持久化；runtime context 和 LLM-visible context 也不是一回事。

本项目对应结论：

```text
Conversation Session
≠
Canonical Business State
```

## LangGraph

```text
thread_id ≈ chatId
checkpoint ≈ versioned state snapshot
```

但本项目没有 graph execution resume/time-travel，所以不能说“等价 LangGraph”。我们主要自己实现餐饮业务特化的 reducer、Task、Reference、OCC。

## Rasa CALM

对 Turn Semantics 最有参考意义：

```text
User Message
→ high-level Commands
→ deterministic Dialogue Manager
```

一条消息可以产生多个 command，比 single-intent label 更适合 compound turn。

---

# 二十三、牛客/面试高频追问

## Q1：为什么不用 History 直接做 Memory？

History 是语言证据，不是当前业务真值。取消、覆盖、Task 切换、ordinal reference、并发 version 都需要结构化状态。

## Q2：为什么不用 LangGraph？

当前 Java/Spring Boot 主流程高度确定，复杂的是业务 invariant，不是 graph 编排。若未来变成长时任务、跨小时中断、人工审批，再考虑 durable workflow runtime。

## Q3：为什么 Full Snapshot？

当前状态规模小，Full Snapshot 读/恢复/debug 简单。代价是长期增长，未来再 compaction，不提前引入 Event Sourcing。

## Q4：OCC 和 stale result guard 有什么区别？

OCC 防并发写覆盖；stale guard 防慢 Tool 基于旧世界产生的结果被继续当作有效事实。

## Q5：为什么 Mention 不能直接写 slot？

“这个日本料理怎么样”里的“日本料理”是 reference qualifier，不是 `SET cuisine`。Entity Mention 和 Dialogue Act 必须分离。

## Q6：为什么行政区不能全交 LLM？

行政 identity 是 closed-world fact，模型可以给 hint，但最终 identity 必须 resolver/provider 校验。

## Q7：为什么 GPS 不能直接兜底 Named POI？

GPS 是设备位置，不是用户命名目标。“农大附近”没解析成功时偷用 GPS，会把“我要去农大”变成“我附近”。

## Q8：那为什么现在 GPS 又能参与“农大”解析？

它只做 disambiguation prior：帮助候选排序，不直接替代 POI identity。

## Q9：为什么 RecommendationBatch 还要存 cuisine/referenceTags？

历史引用不仅有 ordinal，还会有“日本料理那个”。需要轻量 grounding metadata，但不能复制完整 evidence。

## Q10：为什么“重口吗”不能查 detail？

detail 是静态商户事实；口味属于 evidence-backed subjective fact，需要评论/笔记证据。

## Q11：Evidence 没提重口怎么办？

返回 `topicMatched=false`，明确证据不足；可以展示一般评价，但不能据此下“重口/不重口”结论。

## Q12：为什么 DecisionSession 不能覆盖 WM？

DecisionSession 是 execution snapshot，不是 canonical current-state writer。合法 command 必须通过 reducer/projection 改 WM。

## Q13：为什么无结果不能自动把用户硬条件全放宽？

这是未经用户授权的状态 mutation。系统可以提出 options；用户明确恢复后再执行 command。

## Q14：怎么防止只对评测集过拟合？

```text
不写 CaseCode/shopId/具体商户 hardcode
真实 Bad Case 提炼成 invariant
targeted 后跑 full
最终跑 holdout
再做自然语言 HTTP smoke
```

---

# 二十四、最值得背的 20 个不变量

```text
1. Chat History ≠ Canonical Business State
2. Current Candidate Projection ≠ Historical Recommendation Fact
3. Conversation 可以同时存在多个 Task
4. 一个 Turn 只能有一个 authoritative WM Snapshot
5. 跨 Turn 并发靠 OCC
6. 旧 Runtime Result 不能覆盖新 State
7. Reference 属于 pre-mutation snapshot
8. Criteria Mutation ≠ Execution Action
9. Value ≠ Intent ≠ Provenance ≠ Projection
10. Administrative Level ≠ Administrative Identity
11. String Mention ≠ Entity Mention
12. Partial Dataset ≠ Complete World
13. Location Mention ≠ Resolved Identity ≠ Executable Search Anchor
14. GPS prior ≠ Named POI identity
15. Mention ≠ Mutation
16. Extracted Delta ≠ State Write Permission
17. DecisionSession Execution Fact ≠ Canonical Current Criteria
18. Pure Deictic Reference ≠ Qualified Reference
19. Static Shop Detail ≠ Evidence-backed Subjective Fact
20. Failure State 必须有可解释事实 + 可执行 Recovery Command
```

---

# 二十五、当前明确不能吹的地方

```text
Task Identity 仍是业务启发式
TurnCommandSet 不是通用 Agent DSL
Compound Intent 还不是任意多动作 Planner
Full Snapshot 长期会增长
Provenance 还没有完整 sourceTurn/sourceEvent/timestamp/confidence/expiry
行政 registry 是 partial，需要 provider fallback
当前 POI provider 只有已确认的 maps_geo，简称/校区自动解析能力受 provider 能力限制
描述性 qualifier matching 仍是轻量 deterministic grounding，不是通用 entity linker
ShopFactQueryType 是业务 taxonomy，不是开放域 QA ontology
Run141/142/143 仍有真实失败，不应包装成生产级通用 Agent
```

---

# 二十六、面试官连续追问链

## 链 1：Working Memory

```text
为什么不用 History？
→ History 不是当前 truth

为什么 Flat WM 不够？
→ 多 Task Ghost Inheritance

为什么 Task 后还要 same-turn snapshot？
→ 一个请求出现两个世界

为什么还要 OCC？
→ 跨请求并发

慢 Tool 呢？
→ stale runtime result guard
```

## 链 2：Turn Semantics

```text
有 WM 为什么“这个日料咋样”还会错？
→ 写状态前没有 canonical turn semantics

Extractor 不就是理解用户的吗？
→ 它提供 Delta 内容，不拥有写权限

第一版 Turn Semantics 为什么还返工？
→ route 里残留第二 Mutation Authority
```

## 链 3：Location

```text
为什么 Named Location 不能用 GPS 兜底？
→ intent 不同

为什么“鼓楼”不能模型说了就算？
→ administrative identity authority

为什么“福州大学”不能 substring 成福州市？
→ string mention ≠ entity mention

为什么“农大”又要看 GPS？
→ GPS 只做 POI disambiguation prior
```

## 链 4：Reference / Fact

```text
为什么“这个日本料理”不等于 focused？
→ deictic + qualifier

Batch 为什么补 cuisine/referenceTags？
→ historical descriptive grounding

为什么“重口吗”不能 detail？
→ evidence-backed subjective fact

没证据怎么办？
→ topicMatched=false + uncertainty
```

## 链 5：No-result

```text
为什么不自动放宽？
→ 未经授权不能改硬条件

“那附近有啥”怎么办？
→ BROADEN_FOOD_SCOPE

为什么不只改 DecisionSession？
→ WM 才是 canonical current state

为什么第一次失败就要解释范围？
→ Failure Explanation 是恢复协议的一部分
```

---

# 二十七、最终收口

> **这个项目真正的主线，不是“做了一个餐饮聊天机器人”，而是把一个最初依赖 History 和模型重解释的多轮系统，逐步收敛成 `Turn Semantics → Entity/Reference Grounding → Policy/FSM → Deterministic Reducer → Canonical Working Memory → Tool/DecisionSession → Evaluation`。每次重构都来自一个真实的“双真相、authority leak 或 representation gap”，而不是为了堆 Agent 组件。**
