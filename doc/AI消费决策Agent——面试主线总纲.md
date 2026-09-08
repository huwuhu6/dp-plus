# AI 消费决策 Agent——最终面试复习手册

> 这是当前唯一面试主资料。后续复习优先看本文，不再到开发记录、待修清单、旧面试稿里来回找。
>
> 当前 Code Truth：main `8611c2fc65f0470ed18bb5b84171ad8520f88359`
>
> 当前归档验证：Unit Tests `402 / 0 failures / 0 errors / 3 skipped`；Robustness Run144、Conversation-v1 Run145、Holdout Run146。
>
> 统一回答结构：**现象 → 为什么第一版不行 → 根因 → 最终方案 → trade-off → 验证 → 当前边界。**

---

# 一、先记住项目主线

这个项目不是“做了一个会推荐餐厅的 ChatBot”，核心是解决**多轮消费决策 Agent 的状态一致性、实体 Grounding、Tool Grounding 和可评测性**。

最终主线可以压成：

```text
用户自然语言
→ Context / Turn Understanding
→ Intent Routing
→ Reference / Location Grounding
→ Criteria Reduction
→ Policy / FSM
→ Retrieval / Tool Execution
→ Canonical Working Memory / DecisionSession
→ Response / SSE
→ Evaluation
```

职责划分：

```text
LLM                  → 开放语义理解、结构化提议
Resolver             → 实体 / 引用 Grounding
Turn Semantics       → 决定本轮有没有状态写权限
Reducer              → 确定性修改业务状态
FSM / Policy         → 判断动作当前是否合法
Tool / Provider      → 获取外部事实
Working Memory       → 当前 canonical business state
DecisionSession      → 某次执行事实
RecommendationBatch  → 历史推荐身份、顺序和轻量引用信息
Evaluation           → 检查多轮 trajectory 是否满足 Contract
```

一句话：

> 我做的核心不是让模型“更会聊”，而是把原本隐含在 History 和 Prompt 里的多轮业务语义，逐步收敛成有明确 authority、可持久化、可恢复、可审计、可评测的状态系统。

---

# 二、项目介绍：30 秒 / 2 分钟 / 5 分钟

## 1. 30 秒版本

我做的是一个本地生活餐饮消费决策 Agent。它和普通单轮 RAG 最大的区别，是我重点解决多轮决策状态管理：用户会不断切换地点、预算、菜系，会换一批推荐，会引用“最开始第二家”，会反悔条件，也会问“这个日本料理重口吗”“我什么时候说过安静”。如果每轮都依赖 Chat History 和 LLM 重新理解，很容易出现状态串扰、历史引用漂移、事实问题误改搜索条件。

所以我最后把系统收敛成 Task-scoped Working Memory、RecommendationBatch、Turn Semantics、Reference Resolver、Location Entity Resolution、确定性 Reducer/FSM、Milvus 语义检索和离线多轮评测，让模型负责开放语义理解，Java 负责状态 authority 和业务执行。

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

项目一开始没有 canonical Working Memory，很多状态散在 History、当前请求 Context、candidatePool 和 focusedShop 里。随着真实对话和评测增加，连续暴露几类问题：

```text
换一批后历史顺序丢失
→ RecommendationBatch

福州火锅 → 杭州日料 → 回到最开始方案时条件串扰
→ Task Scope

同一请求里重新 load 旧状态
→ same-turn authoritative snapshot

并发旧请求 / 慢 Tool 回来覆盖新状态
→ OCC + stale runtime result guard

“第一家太贵了，第二家有插座吗”同时包含状态修改和事实追问
→ TurnPlan

“这个日料咋样”只是提到日料，却被写成 cuisine=日料
→ Canonical Turn Semantics

GPS、行政区、POI 混用
→ Location Authority + Entity Resolution

“最开始第二家”“日本料理那个”
→ Batch-aware Reference Resolver

“为什么推荐”“我什么时候说过安静”
→ Decision Context Query + Provenance

0 结果后“那附近有啥”无法恢复
→ WAITING_RELAXATION + BROADEN_FOOD_SCOPE

“重口吗”不能靠店铺静态详情或 LLM 猜
→ Shop Fact Type → Evidence Tool Contract
```

最终原则是：**LLM 提议，代码裁决；History 是证据，不是当前真相；Search 是候选召回，不等于 Entity Resolution。**

## 3. 5 分钟版本应该怎么展开

先讲项目目标，再按下面 5 条主线展开，不要一上来堆类名：

1. Working Memory / Task / RecommendationBatch：解决状态和历史身份。
2. Turn Semantics / Reference：解决“用户这轮到底允许系统做什么”。
3. Location Entity Resolution：解决命名地点、GPS、行政区、POI 的 authority。
4. Retrieval / Tool：解决结构化约束、语义召回和事实 Grounding。
5. Evaluation：解释为什么最终停止 case chasing，而不是把所有 Case 修到 100%。

---

# 三、几个概念必须彻底分清

```text
Chat History
= 用户和系统说过什么，是语言证据

ChatProcessingContext
= 当前 Turn 的 runtime 临时数据

ConversationWorkingMemory
= 系统当前相信的 canonical business state

DecisionTaskState
= 某一套消费决策任务的当前条件、搜索位置和历史推荐批次

DecisionSession
= 某一次决策执行时的输入、输出、状态和历史事实

RecommendationBatch
= 某一轮推荐候选的身份、顺序、decisionSessionId 和轻量 reference metadata

Turn Semantics
= 用户这一轮允许系统做什么

Resolver
= 把自然语言 mention Ground 到可执行实体
```

最重要的两句话：

> **Canonical Working Memory 回答“系统现在相信什么”；Canonical Turn Semantics 回答“用户这一轮允许系统改变什么”。**

> **History 里说过，不等于现在仍生效；Execution 当时发生过，也不等于当前 WM 现在是什么。**

---

# 四、核心面试题 1：什么是 AI Agent？你的项目和普通 ChatBot 有什么区别？

## 解释

牛客近年的 Agent/AI 应用面经非常高频地先问这个问题。不要背“感知-规划-行动”定义后结束，要落回自己的项目。

普通 ChatBot 可以主要完成：

```text
用户输入 → 模型生成 → 文本回复
```

而 Agent 至少多了：

```text
状态
决策
工具调用
外部环境交互
执行结果反馈
多轮恢复
```

## 面试回答

我理解 Agent 和普通 ChatBot 的核心差别，不在于有没有 Function Calling，而在于系统是否维护任务状态、根据状态选择动作，并通过 Tool 改变或读取外部世界。

我的项目里，模型并不直接拥有所有控制权。LLM 负责理解用户的开放语义，Resolver 负责实体 Grounding，Working Memory 保存当前业务真相，FSM/Policy 判断动作是否合法，Tool 获取地图、商户、评价等外部事实，最后再根据执行结果进入下一状态。比如用户说“最开始第二家重口吗”，系统要先从历史 RecommendationBatch Ground 到具体商户，再判断这是 EVIDENCE 查询，然后调用评价证据 Tool，而不是直接让 LLM 自由回答。

## 常见追问

**Agent 必须有 Planner 吗？**

不一定。我的业务不是开放式长任务规划，而是高度受约束的多轮消费决策，所以采用“LLM 语义理解 + Java 确定性 Pipeline/FSM”比完全自由 Planner 更稳定。

---

# 五、核心面试题 2：为什么不用 Chat History 直接做 Memory？

## 解释

History 是 observation，不是 canonical state。

例如：

```text
福州火锅100
→ 预算改150
→ 算了还是之前100
```

History 能告诉你三句话都说过，却不能天然给出“当前预算=100”这个确定性结果。

## 面试回答

我没有把 Chat History 当业务状态，因为 History 只适合提供语言证据，不适合承担覆盖、清除、恢复、并发版本这些确定性语义。如果每轮都让 LLM 从整段 History 重新推断当前条件，同一段历史可能得到不同状态，而且无法对状态变化做精确测试。

所以我把 Memory 拆成两类：Chat History 保留语言上下文，ConversationWorkingMemory 保存当前 canonical business state。模型可以提出新的约束，但只有经过 Turn Semantics、Reducer 和 Policy 校验后才能真正写入 WM。

## 追问：短期记忆和长期记忆怎么理解？

项目里更准确的说法是：

```text
短期语言上下文 → Chat History / 当前 Turn Context
业务工作记忆   → Working Memory
历史决策事实   → DecisionSession / RecommendationBatch
长期知识       → 商户画像、Review、Milvus 向量索引
```

不要把所有东西都叫“Memory”。

---

# 六、核心面试题 3：为什么 Flat Working Memory 后来要改成 Task Scope？

## 解释

真实场景：

```text
福州火锅100
→ 杭州西湖日料300
→ 还是最开始那套
```

如果只有一套 flat criteria，容易恢复成“福州 + 火锅 + 西湖 + 300”的幽灵组合。

## 面试回答

一开始我认为这是字段 merge 的问题，后来发现根因其实是用户同时维护了多套 Decision Task。于是把 Working Memory 从一套 Flat Criteria 改成 tasks[]：福州火锅是一套 Task，杭州日料是另一套 Task。用户说“还是最开始那套”时，本质不是重新拼字段，而是 re-activate 历史 Task。

这也是我对 Agent state 的一个认识：有些问题不是“slot 更新规则不够复杂”，而是 representation 本身错了。

## 追问：为什么预算不做 Task Identity？

预算通常属于 refinement。如果预算也参与 identity，那么福州火锅 100、80、120 会碎成三个 Task。当前 Task Identity 更偏目的地 + 核心需求，是业务启发式，不包装成通用 Task Manager。

---

# 七、核心面试题 4：为什么要 RecommendationBatch？candidatePool 不够吗？

## 解释

```text
第一批 A/B/C
→ 换一批 D/E/F
→ “最开始第二家怎么样？”
```

只有 `shownShopIds=[A,B,C,D,E,F]`，无法恢复“第一批第二个=B”。

## 面试回答

candidatePool 只表示当前候选，shownShopIds 只能表示“展示过谁”，都丢失了 batch boundary 和 ordinal。因此我增加 RecommendationBatch，保存 `decisionSessionId + candidates[]`。当前 candidate projection 仍来自最新 Batch，但历史引用可以按 Batch 和 ordinal 解析。

后来真实聊天又出现“日本料理那个”，所以 CandidateRef 只补了 cuisine/referenceTags 这类轻量引用信息，没有把完整评论和 evidence 复制进每个 WM snapshot。历史证据仍放在 DecisionSession.resultJson。

## 追问：为什么不把所有历史结果直接塞 WM？

会导致 snapshot 膨胀、历史事实重复、更新复杂。WM 只保留完成 reference Grounding 所需的轻量 identity metadata。

---

# 八、核心面试题 5：为什么 Reference 必须在 mutation 前解析？

## 解释

```text
“第一家太贵了，第二家有插座吗？”
```

这一轮同时包含：

```text
第一家太贵 → Criteria Mutation
第二家有插座吗 → Business Follow-up
```

如果先改条件导致 candidate invalidation，再解释“第二家”，ordinal 可能漂移。

## 面试回答

我的原则是 `Reference belongs to pre-mutation snapshot`。用户说“第二家”时指的是发话瞬间看到的第二家，因此先基于 mutation 前的 RecommendationBatch resolve reference，再执行状态修改和事实查询。否则系统会用修改后的世界解释修改前的语言。

---

# 九、核心面试题 6：为什么引入 TurnPlan？一个 Intent 不够吗？

## 解释

真实语言经常不是单 Intent。

```text
第一家太贵了，第二家有插座吗？
```

不能强行二选一：改条件 or 问事实。

## 面试回答

原来“一个 Turn 一个 Action”的模型不够表达 compound turn。所以我把 TurnPlan 拆成两条正交轴：`CriteriaIntent = NONE / APPLY_DELTA` 和 `ExecutionAction`。这样一轮可以一边修改条件，一边执行 Business Follow-up。

这比新增越来越多复合 Intent 稳定，因为“状态要不要改”和“本轮执行什么动作”本身就是两个维度。

---

# 十、核心面试题 7：Canonical Turn Semantics 是什么？为什么有 WM 还不够？

## 解释

真实 Bad Case：

```text
“这个日料咋样？”
```

Extractor 可能正确识别到“日料”，但用户只是描述一家店，并没有要求：

```text
SET cuisine = 日料
```

## 面试回答

Working Memory 解决了“当前状态的唯一真相”，但没有解决“谁有权修改这个真相”。所以后来我增加 Canonical Turn Semantics。

核心原则是：

```text
Mention ≠ Mutation
Extracted Delta ≠ State Write Permission
Fact Question ≠ State Update
```

TurnUnderstandingService 先判断用户这一轮是否真的有 mutation intent；只有允许修改时，ConstraintExtractor 的 Delta 才能进入 Reducer。这样模型可以识别出“日料”这个实体，但不能仅因为抽到了字段就获得状态写权限。

## 追问：为什么第一版 Turn Semantics 还返工？

因为当时 `selectAction()` 已经走新的 TurnUnderstanding，但旧 route 分支仍用 `hasMutation(criteriaDelta)`，实际存在两个 Mutation Authority。最终把 reference mutation 统一收口到同一 TurnUnderstanding authority，缺失时 fail-closed。

---

# 十一、核心面试题 8：same-turn snapshot、OCC、stale result guard 分别解决什么？

## 解释

三者作用域不同。

### same-turn snapshot

曾经出现：

```text
transitionTask() 修改当前 WM
→ reduceCriteria() 又从 DB load 旧 WM
```

同一请求出现两个世界。

### OCC

解决两个并发 Turn：

```text
A 基于 v20
B 写到 v21
A 再写
→ expectedVersion != latest
```

### stale runtime result guard

解决慢 Tool：

```text
A 基于 v20 调慢 Tool
B 写 v21
A 5 秒后返回
```

即使不让 A 写旧 WM，A 的 Tool Result 本身也已经基于旧世界计算。

## 面试回答

我最后把它们分成三个层次：same-turn snapshot 保证一个请求内部只看一个 authoritative WM；OCC 防止跨请求旧状态覆盖新状态；stale runtime result guard 则在 Tool Result 回写前比较 baseWorkingMemoryVersion 和 latestVersion，防止旧世界计算出来的结果继续作为有效事实。

一句话：

> same-turn 管单请求世界观，OCC 管并发写，stale guard 管慢执行结果。

---

# 十二、核心面试题 9：为什么不用 Event Sourcing？为什么用 Full Snapshot？

## 面试回答

当前 Working Memory 规模可控，而且业务更关注“读当前状态、恢复某个版本、定位问题”。Full Snapshot 的实现和调试成本低，版本恢复也直接。

Conversation Event 主要承担 audit/diagnostics，并不是通过 replay event 重建所有状态，所以项目不是 Event Sourcing。

代价是长会话 snapshot 存储会增长。如果未来状态量明显变大，可以考虑 periodic full snapshot + delta/compaction，但目前没有必要提前承担 Event Sourcing 的复杂度。

---

# 十三、核心面试题 10：DecisionSession 和 Working Memory 为什么不能互相覆盖？

## 解释

无结果放宽时曾出现：

```text
DecisionSession：已经清掉兰州拉面
WM：仍然 keyword=兰州拉面
```

但也不能让 DecisionSession.result 全量反写 WM，因为 Execution Result 里可能有临时执行值。

## 面试回答

我把两者职责分开：Working Memory 是 canonical current state，DecisionSession 是某一次 execution fact。合法的状态变化必须通过白名单 Command Projection 更新 WM，而不是把整个 DecisionSession.constraints 覆盖回去。

例如 BROADEN_FOOD_SCOPE 只明确清 keyword/cuisine；EXPAND_RADIUS 只同步 radius。这样避免执行层临时值偷渡进 canonical state。

---

# 十四、核心面试题 11：Location 为什么改了很多次？最终 Contract 是什么？

## 解释

最后抽象为：

```text
Location Mention
→ Geographic Context
→ Candidate Retrieval
→ Candidate Resolution
→ Canonical POI
→ Search Anchor
```

关键不变量：

```text
Value ≠ Intent ≠ Provenance ≠ Projection
Administrative Level ≠ Administrative Identity
String Mention ≠ Entity Mention
Partial Dataset ≠ Complete World
Location Mention ≠ Resolved Identity ≠ Executable Search Anchor
GPS prior ≠ Named POI identity
Retrieval ≠ Resolution
```

## 面试回答

Location 最开始只是几个 province/city/area 字段，真实聊天后发现 GPS、行政区、POI 和命名地点其实是不同 authority。

比如设备 GPS 在福州，用户说“北京农大附近”，显式北京必须高于设备 GPS；又比如“福州大学”包含“福州”，不能 substring 成用户设置了福州市；“师大”也不能因为高德搜出几个相关 POI 就直接当成最终搜索地点。

所以最终设计是：行政身份由 AdministrativeRegionResolver/provider 验证，POI 由地图 Provider 召回候选并做 Entity Resolution，GPS 只作为当前设备位置或短 POI 的辅助消歧信号。只有 canonical POI/明确行政范围确认后，才成为最终 searchLocation。

---

# 十五、核心面试题 12：显式地点和 GPS 冲突时怎么处理？

## 面试回答

我的优先级是：

```text
Explicit Named Geographic Context > Device Location Bias
```

真实问题是：设备 GPS 在福州，用户说“北京农大”。早期因为“北京”无“市”后缀且本地 registry 没命中，系统没拿到 activeCity，于是 POI Around 被福州 GPS 带偏。

后来增加专门的 `resolveGeographicContextPrefix()`：本地 miss 时允许行政 Provider 验证 suffixless 省/市，但只接受唯一、精确 alias 对应的省/市结果，不放宽普通行政解析规则。于是“北京农大”会变成 `region=北京市 + keywords=农大 + TEXT`，而单独“农大”在有 GPS 时才允许 Around 消歧。

---

# 十六、核心面试题 13：“师大”这种简称最后怎么处理？为什么不维护 alias 表？

## 解释

不能维护：

```text
师大 → 福建师范大学
农大 → 福建农林大学
福大 → 福州大学
```

因为换城市立即失效，而且会无限 case chasing。

## 面试回答

我把地图 Search 当 Candidate Retrieval，不把它直接当 Entity Resolution。用户说“师大”时，先结合显式城市或 GPS 获得地理上下文，再调用高德召回候选；候选排序现在以**名称相关度 > 类型辅助 > GPS 距离**为主，确认 canonical POI 后才写 searchLocation。

我中间踩过一个反例：为了过滤“师大分店”的餐馆，一度把“师大”推断成 UNIVERSITY 并做硬类型过滤，但“福建师范大学附属小学”马上击穿这个方案。后来撤掉 UNIVERSITY hard gate，不继续扩 PRIMARY_SCHOOL/MIDDLE_SCHOOL taxonomy。`poiType/typeCode` 只保留为辅助 metadata，不能凌驾于明确名称匹配。

这次最大的结论是：

> Search 是召回，Resolution 才是实体确认；Location 是基础设施，不能为了几个 Case 自己重造地图 POI ontology。

---

# 十七、核心面试题 14：用户说“我说的是福建师范大学”为什么以前会失败？现在怎么做？

## 解释

旧逻辑里 Location clarification 即使拿到用户新文本，`activeCriteria.targetArea=师大` 仍会覆盖当前文本，因此又搜一次“师大”。

## 面试回答

现在 clarification turn 有新的 authority：如果用户明确提供新的 Named POI，例如“我说的是福建师范大学”，本轮 poiQueryOverride 会覆盖旧 targetArea，但不会立刻污染 durable criteria。

优先级是：

```text
1. 当前文本唯一匹配 pending candidate → 直接走统一 canonical confirm contract
2. 当前文本是新的明确 POI → 用新名称重新 Provider 验证
3. 仍无法唯一确定 → 保持 CLARIFYING
```

按钮确认和自然语言确认最后汇入同一个 `acceptPendingSearchLocation` / canonical searchLocation contract。

---

# 十八、核心面试题 15：无结果后为什么不能直接自动放宽？

## 解释

真实链：

```text
兰州拉面
→ 0 结果
→ 没有吗
→ 我就要兰州拉面
→ 那附近有啥
```

自动把所有硬条件清掉属于未经用户授权的 mutation。

## 面试回答

我把无结果设计成明确状态 `WAITING_RELAXATION`，再定义 Recovery Command。比如 `BROADEN_FOOD_SCOPE` 只在已有具体 food target 且用户明确表达泛化时合法：清 keyword/cuisine，但保留 location、radius、budget、其他 preference 和历史 RecommendationBatch。

另外失败解释也做成统一 formatter，首次 0 结果和用户追问“什么意思”都从同一份 Decision facts 生成搜索范围、当前条件、自动放宽历史和下一步选项，避免两个地方各拼一套文案导致事实漂移。

---

# 十九、核心面试题 16：怎么降低 Agent 幻觉？

## 解释

牛客 Agent 面经非常常问“幻觉怎么办”，不要只回答 Prompt。

## 面试回答

我把幻觉分成三类治理：

### 1. 状态幻觉

模型不能直接改 canonical WM。通过 Turn Semantics、Reducer、FSM、OCC 控制状态写权限。

### 2. 实体幻觉

行政区、POI、商户引用不能只相信模型名字；必须经过 Resolver / provider / RecommendationBatch Grounding。

### 3. 事实幻觉

“重口吗”“评价怎么样”这类外部事实必须走 Tool。模型只负责判断 `STATIC_DETAIL / EVIDENCE / VOUCHER`，事实本身来自数据库、Review 或外部 Provider。

所以项目不是用一句 System Prompt 要求“不要幻觉”，而是尽量把模型从最终 authority 位置拿掉。

---

# 二十、核心面试题 17：“重口吗”为什么不能直接 get_shop_detail 或让 LLM 回答？

## 面试回答

Shop detail 适合地址、人均、营业时间这类静态事实；“重口、辣不辣、环境、服务、排队、适合约会”属于 evidence-backed subjective fact。

所以项目把商户事实查询分成：

```text
STATIC_DETAIL
EVIDENCE
VOUCHER
```

主观体验必须走 `search_shop_evidence`。如果 Review 没有明确提到主题，则返回 `topicMatched=false`，可以展示有限的一般评价，但不能据此下“重口/不重口”的确定结论。

核心原则：

> Semantic classification 可以 probabilistic，Fact acquisition 必须 grounded。

---

# 二十一、核心面试题 18：你的 RAG / 检索链路到底是什么？

## 先纠正一个口径

**不要面试时说“所有硬过滤都在 MySQL 下推”。当前真实代码不是这样。**

当前 `retrieveAndRank()` 的真实链路是：

```text
行政区 / excludeShopIds
→ MySQL SQL 粗筛 Shop
→ 加载 AiShopProfile
→ Java matchesHardConstraints 做预算、菜系、距离/营业等 hard constraint
→ 得到 hardMatched shops
→ Milvus 语义召回只在 hardMatched 候选范围内评分
→ Java deterministic rerank
→ Top N + evidence 组装
```

所以更准确的口径是：

> **行政 scope SQL 粗筛 + Java 硬约束过滤 + Milvus 受约束语义召回 + 确定性重排。**

## 面试回答

我的场景不是传统“文档切片问答 RAG”，而是本地生活检索。结构化约束和语义偏好要分层处理。

预算、地点、营业状态、排除菜系等属于 hard constraints，必须先缩小 candidate universe；“适合聊天”“氛围好”“口味偏清淡”这类软语义再交给 Embedding/Milvus。

Milvus 里主要是商户 Profile 和 Review Evidence。语义召回结果按 shop 聚合后形成 semanticScore，再和星级、距离、人均偏离等确定性业务分结合重排。这样向量相似度不能越过硬约束把不合格商户重新带回来。

## 追问：为什么不用纯向量检索？

纯向量不能稳定满足预算、区域、营业等硬条件；语义相似只是相关性，不是业务合法性。

## 追问：为什么不用 BM25？

当前主链没有 BM25。原因是这个版本重点是结构化 hard filter + semantic profile/review recall。BM25 对“精确关键词/店名/菜名”可能有价值，但没有真实实验就不包装成已落地方案。如果未来加 Hybrid Search，需要用 Recall@K/MRR/证据命中率做对比，而不是因为“混合检索更高级”就加。

---

# 二十二、核心面试题 19：Embedding、Milvus、Rerank 分别解决什么？

## 面试回答

Embedding 负责把 query 和商户画像/Review 映射到向量空间，Milvus 做 ANN 语义检索，Rerank 则把语义相似度重新放回业务目标里。

我的系统不会直接按 vector score 排最终结果，因为消费决策还有：

```text
星级
距离
人均预算偏离
硬约束是否满足
证据覆盖
```

所以 semanticScore 只是最终 score 的一个组成部分。

当前配置里存在 `semanticMinScore=0.35`、`semanticTopK=80`、`semanticWeight=18` 等单点值，但**没有做足够系统的全参数调优**。面试时不要说这些参数是“最优参数”。正确口径是：已经做了检索实验和 A/B/ablation 验证，但完整参数选择仍应结合 Recall@K、MRR、证据命中率、检索耗时和端到端效果联合调优。

---

# 二十三、核心面试题 20：向量库怎么做增量更新？

## 面试回答

我没有用“每天全量重建 Milvus”作为唯一方案，而是增加了 durable Vector Sync Task。

核心设计：

```text
稳定 documentId
Profile: shop-profile-{shopId}
Review:  shop-review-{reviewId}

业务数据变更
→ 写入 tbl_ai_vector_sync_task 的 latest desired state
→ operation=UPSERT/DELETE + targetRevision
→ Scheduler 周期扫描 due task
→ claim lease
→ Worker 写 Milvus
→ mark SYNCED
→ 失败指数退避重试
→ lease 过期可恢复
```

`VectorSyncTaskService` 只负责持久化“这个文档最终应该是什么状态”，不做网络 IO。Worker 才真正调用 Milvus。这样数据库事务和外部向量库写入解耦，失败可以重试。

为了避免旧任务覆盖新任务，task 按稳定 documentId 合并 latest desired state，并带 targetRevision；worker markApplied 时如果发现状态已经更新，会 requeue newer state。

另外 Reconciliation 只负责发现逻辑 drift 并重新生成 durable sync intent，不直接旁路写 Milvus。

## 追问：为什么不直接事务里写 Milvus？

MySQL 和 Milvus 没有一个本地 ACID 事务，直接在业务事务里调用外部向量库会把网络失败耦合进主事务。durable task + retry 更容易实现最终一致性和可恢复性。

---

# 二十四、核心面试题 21：SSE 为什么这么设计？和 WebSocket 怎么选？

## 面试回答

项目增加了 `POST /ai/chat/messages/stream`，用 Spring `SseEmitter` 流式返回，但同步 `/ai/chat/messages` 仍保留兼容。

SSE 适合我的场景，因为主要是服务端单向推送：

```text
status
text_delta
ui_component
suggested_chips
complete
```

没有高频双向实时控制需求，所以没必要为了全双工引入 WebSocket 的连接管理复杂度。

流式执行复用原 `ChatOrchestrationService`，不是另写第二套 Agent 逻辑。异步线程里显式恢复 UserHolder，确保权限、Working Memory、DecisionSession 和同步入口一致。Controller 还设置了 `X-Accel-Buffering: no` 和 `Cache-Control: no-cache`，避免 Nginx 把 SSE 缓冲成“最后一次性返回”。

---

# 二十五、核心面试题 22：Tool 调用异常、超时、乱调工具怎么办？

## 面试回答

我不会让 Planner 的 Tool Call 直接成为最终业务动作。

主要分三层：

1. **Tool Contract**：不同问题类型只允许对应工具，例如 subjective evidence 不能用静态 detail 代替。
2. **参数/状态校验**：Reference、Location、Decision command 先 Ground/Validate，再执行 Tool。
3. **失败降级**：Tool 超时/失败时根据是否是关键事实决定降级、澄清或终止，不能拿空结果让 LLM 自己补答案。

对于有副作用的工具，还要额外考虑幂等、重试安全和 requestId；当前餐饮 Tool 主要是读操作，风险比支付/下单类 Agent 低，所以没有把分布式 Saga 之类复杂机制硬搬进来。

---

# 二十六、核心面试题 23：为什么不用 LangChain / LangGraph / Multi-Agent？

## 面试回答

我不是排斥框架，而是当前系统复杂点主要在业务 invariant，不在任意图编排。

Spring Boot 主链高度确定：理解 → Grounding → Reduce → Policy → Execute。LangGraph 的 checkpoint/thread 思路和我的 versioned state 有相似点，但项目没有长时间 suspend/resume 的任意 graph workflow，也没有人工审批节点，所以没必要为了框架重写 Java 主链。

Multi-Agent 也一样：消费决策当前没有明显的独立角色协作收益。如果硬拆“地点 Agent、推荐 Agent、评价 Agent”，反而增加上下文传递、一致性和成本问题。只有未来出现真正独立、可并行、边界清楚的长任务，才值得考虑 Multi-Agent。

一句话：

> 我优先解决 authority 和 state invariant，而不是为了“看起来像 Agent”堆 Agent framework。

---

# 二十七、核心面试题 24：你怎么做 Agent Evaluation？

## 解释

不是只看最终答案有没有“像人话”。

项目 Conversation Evaluation 会通过真实聊天入口执行多轮 trajectory，并检查：

```text
Route
Tool
Final Status
Locality
Working Memory projection
Context Rewrite
历史引用 / Task 切换 / unseen recommendation 等 Contract
```

测试层次：

```text
Unit / targeted
→ 局部不变量

HTTP directed smoke
→ 真实主链 wiring

conversation-robustness-v1
→ 压边界、多轮恢复、Location/Task/Reference

conversation-v1
→ 主功能轨迹

conversation-holdout-v1
→ 检查对固定 Dataset 的过拟合

人工真实口语 smoke
→ 简称、省略、deictic、前端真实表达
```

## 面试回答

我最后不再把“评测总分”当唯一目标，而是看多个 Contract。Complete 是多个断言的 AND，所以不能简单说 Complete 21/48 就代表只有 21 个 Case 能用。要拆 Route、Tool、Final、Locality 和具体失败聚类。

最终归档时，402 个单测通过；Robustness 相比上版 Complete/Route/Final 各 +1；Holdout 完全持平；Conversation-v1 只有一条 ambiguous probe 新回归。没有模型调用失败。因此我选择封档，而不是为了把每个 Case 修绿继续给生产代码加规则。

---

# 二十八、核心面试题 25：为什么还有失败 Case 就停止开发？

## 面试回答

因为后期已经出现明显的 case chasing 信号。

最典型的是 Location：为了“师大”加 UNIVERSITY hard filter，马上被“福建师范大学附属小学”击穿。如果继续补 PRIMARY_SCHOOL、MIDDLE_SCHOOL、HOSPITAL、MALL……就会变成针对 Dataset 写规则，而不是解决实体解析本身。

所以我最后的停止条件是：

```text
核心单测全绿
Robustness 无系统性下降且小幅改善
Holdout 不退化
新增回归规模小且可定位
没有模型/API执行异常
```

Evaluation 的目标是发现系统性问题，不是诱导生产代码拟合测试集。

---

# 二十九、牛客 2026 高频补充题：这些必须准备

近一年的 Java 后端 + AI Agent / AI 应用面经，重复出现的主题非常集中：Agent vs ChatBot、Memory、RAG、Tool Calling、幻觉、Tool 超时/重试、SSE、监控评测、模型选型、框架选型、多 Agent、项目真实性和 AI Coding。下面都按本项目口径回答。

## Q26：RAG 完整 Pipeline 是什么？

通用 RAG 可以回答：

```text
数据采集
→ 清洗 / 切分
→ Embedding
→ Vector Index
→ Query Understanding
→ Retrieval
→ Rerank
→ Context Assembly
→ Generation
→ Evaluation
```

但必须补一句：我的项目不是典型 PDF 知识库 RAG，而是商户 Profile + Review Evidence 的结构化/语义混合检索。我的主链是行政 SQL 粗筛 + Java hard filter + Milvus semantic recall + deterministic rerank。

## Q27：怎么优化 RAG 召回？

优先从错误类型拆：

```text
硬约束误召回 → 先修 filter / metadata
Query 语义不完整 → query rewrite / semantic query
向量召回低 → embedding、TopK、index/metric
重复/噪声多 → aggregation / rerank / diversity
证据不支持回答 → evidence coverage / topic match
```

不要一上来回答“加 TopK”。TopK 大了召回可能上升，但延迟、噪声和 rerank 成本也会上升。

## Q28：Rerank 怎么做？

我的项目是 deterministic business rerank：semantic score 只是其中一部分，再结合星级、距离、预算偏离等业务信号。

如果是通用知识库，可以再考虑 Cross-Encoder/LLM Reranker；但我的场景候选数和业务结构强，确定性打分更容易解释和控制成本。

## Q29：Function Calling 完整流程？

```text
向模型声明 tool schema
→ 模型输出 tool name + arguments
→ 代码 JSON/schema 校验
→ 权限/状态/业务参数校验
→ 执行 Tool
→ Tool result 作为受信事实返回模型/编排层
→ 生成最终答复
```

关键：**Function Call 是模型提议，不是直接执行权限。**

## Q30：模型返回的 Tool JSON 不合法怎么办？

优先 schema 校验 + deterministic reject/repair，不要把非法参数直接送 Tool。可允许一次有限结构修复重试，但涉及副作用时不能无限自动重试。我的项目更强调 Grounding 和状态校验：地点、引用、Decision option 都必须通过确定性 Contract。

## Q31：LLM 捏造 Tool 参数怎么办？

把参数分为：

```text
模型可生成的开放语义参数
必须从系统状态获取的 identity 参数
必须由用户授权的敏感参数
```

例如 shopId 不应该让模型凭空生成，应从 ResolvedShopReference 得到；canonical POI 坐标来自 Provider，不来自 LLM；当前 Working Memory version 由系统注入。

## Q32：Agent 怎么做监控？

至少要分：

```text
模型层：调用次数、失败率、Token、延迟
Tool 层：toolName、参数摘要、成功率、耗时
决策层：Route、State transition、Relaxation、Fallback
状态层：WM version、OCC conflict、stale result
质量层：Route/Tool/Final/Locality/Recall/MRR/evidence coverage
```

我的项目有 ConversationEvent、DecisionMetric、Evaluation Run/Diagnostics，不只看接口 200。

## Q33：模型怎么选？

不要回答“哪个排行榜最高就用哪个”。

我的原则：

```text
结构化抽取 / 路由 → 稳定 JSON、低延迟、低成本优先
复杂开放生成 → 能力优先
Embedding → 召回质量 + 维度/成本/吞吐
```

而且 Agent 总成本不只由模型单价决定，还包括调用次数、上下文长度、Tool 重试和失败恢复。

## Q34：上下文太长 / Token 消耗太快怎么处理？

项目里不要把完整历史状态都塞 Prompt。结构化业务真相放 WM，历史 identity 放 Batch，历史 execution fact 放 DecisionSession，需要时按引用读取。

一般策略：

```text
窗口裁剪
结构化 memory
按需 retrieval
摘要（但摘要不能替代 canonical state）
减少重复 tool schema / 大段日志
```

## Q35：LLM 服务能不能缓存？

可以，但要看语义。

Embedding、稳定静态生成、相同 prompt 的非个性化结果更适合缓存；带当前 WM、位置、时间、用户上下文的决策结果不能简单按 query 字符串缓存，否则容易返回旧状态答案。

## Q36：Tool 超时重试怎么保证安全？

读 Tool 可以有限重试 + timeout + fallback；写 Tool 要先设计幂等 key、状态机和 side-effect boundary。我的主业务 Tool 当前主要是读，因此重点在 timeout/降级；如果扩展到下单/支付，必须把幂等、补偿、审批放到 Tool Contract 里，而不是让 LLM决定重试。

## Q37：如果让你从零设计一个 Agent，你会分哪些模块？

```text
Input / Session
Turn Understanding
Memory / State
Planner or Router
Resolver / Grounding
Policy / Permission
Tool Registry / Execution
Recovery / Retry
Observation / Trace
Evaluation
```

但不要说“所有项目都必须 10 个模块”。根据业务复杂度裁剪。

## Q38：为什么不是 Multi-Agent？

只有当任务存在真正独立的职责、并行收益、不同工具/权限边界时才拆。否则多个 Agent 只会把单体里的状态一致性问题升级成分布式上下文一致性问题。

## Q39：Prompt Engineering 做了什么？

Prompt 主要约束模型输出结构、上下文边界和可用 Tool，但项目后期最大的改进不是继续堆 Prompt，而是把关键业务 invariant 移到代码：例如 Mention ≠ Mutation、行政身份 Provider 验证、Tool Fact Grounding。

一句话：

> Prompt 负责引导，Contract 负责兜底。

## Q40：你的项目哪些代码是 AI 辅助写的？怎么证明你真的懂？

这个问题现在非常高频，必须诚实。

建议回答：

> 项目开发过程中我大量使用过 Codex/Claude Code 这类 AI Coding 工具辅助检索代码、生成实现和测试，但不是把模型输出当完成标准。我主要负责定义问题、设计状态不变量、审查方案、构造真实 Bad Case、决定哪些方案应该撤回，以及通过 targeted test、HTTP smoke、Robustness/Conversation/Holdout 做验收。比如 Location 中 UNIVERSITY hard filter 是一个看起来合理但会被“福建师范大学附属小学”击穿的方案，我最后选择撤掉而不是继续补 taxonomy。面试时我愿意直接从代码链路、状态变化和评测结果解释每个关键设计。

不要说“代码全是我一行行手写”，也不要说“基本都是 AI 写所以我不清楚”。面试官真正想确认的是：**关键判断力和技术 ownership 在不在你身上。**

---

# 三十、当前真实 Retrieval / RAG 代码事实（防止面试吹错）

这部分必须按当前代码讲。

## 1. 当前硬过滤不是纯 MySQL 下推

真实实现：

```text
Shop QueryWrapper
→ province/city/district/excludeShopIds SQL 粗筛
→ select AiShopProfile
→ Java matchesHardConstraints
→ hardMatched
```

所以面试不要说：

> “预算、菜系、距离、营业时间全部直接生成 SQL WHERE 下推。”

目前这是优化方向，不是当前事实。

## 2. Milvus 在 hardMatched candidate universe 内做语义评分

```text
semanticRetrievalQuery
→ SemanticShopRetriever.recall(... hardMatched ...)
→ shopId → semanticScore
→ 加入 deterministic score
→ sort / TopN
```

## 3. 语义参数不要吹成最优

当前有 `semanticMinScore=0.35`、`TopK=80`、`semanticWeight=18` 等配置，仍属于需要更多系统化实验验证的参数。

## 4. Vector Sync 已经有 durable incremental pipeline

```text
业务变更
→ tbl_ai_vector_sync_task latest desired state
→ Scheduler
→ lease claim
→ Worker
→ Milvus writer
→ synced / retry
```

失败指数退避，最长 delay 有上限；lease 过期可以 recovery。

---

# 三十一、最终归档 Evaluation

## Unit Tests

```text
tests:    402
failures: 0
errors:   0
skipped:  3
```

## Run144 — conversation-robustness-v1

```text
Cases:      48
Complete:   21/48
Route:      44/48
Tool:       47/48
Final:      37/48
Locality:   48/48
Model Failures: 0
```

相对 Run141：

```text
Complete +1
Route    +1
Tool      0
Final    +1
Locality  0
Avg latency -359ms
```

## Run145 — conversation-v1

```text
Cases:      40
Complete:   28/40
Route:      37/40
Tool:       32/40
Final:      40/40
Locality:   40/40
Model Failures: 0
```

相对 Run142：

```text
Complete -1
Route    -1
Tool     -1
Final     0
Locality  0
```

只确认一个新 PASS→FAIL：`ADV_PROBE_AMBIG2`。

## Run146 — conversation-holdout-v1

```text
Cases:      16
Complete:   7/16
Route:      13/16
Tool:       14/16
Final:      12/16
Locality:   16/16
Model Failures: 0
```

相对 Run143 功能指标完全一致。

## 面试怎么解释 Complete 不高

Complete 是多个 contract 的 AND，不是“最终回答能不能用”的单指标。例如一个 Case 的 Route、Tool、Final、WM 全对，但 `unseenRecommendations` 断言失败，Complete 仍为 false。

所以要拆指标和失败聚类，而不是把 Complete 当普通 accuracy。

---

# 三十二、你最应该背的 24 个不变量

```text
1. Chat History ≠ Canonical Business State
2. Conversation 可以同时存在多个 Decision Task
3. Current Candidate Projection ≠ Historical Recommendation Fact
4. 一个 Turn 只能有一个 authoritative WM Snapshot
5. 跨 Turn 并发写靠 OCC
6. 旧 Runtime Result 不能覆盖新 State
7. Reference 属于 pre-mutation snapshot
8. Criteria Mutation ≠ Execution Action
9. Mention ≠ Mutation
10. Extracted Delta ≠ State Write Permission
11. Canonical WM ≠ Canonical Turn Semantics
12. DecisionSession Execution Fact ≠ Canonical Current State
13. Value ≠ Intent ≠ Provenance ≠ Projection
14. Administrative Level ≠ Administrative Identity
15. String Mention ≠ Entity Mention
16. Partial Dataset ≠ Complete World
17. Location Mention ≠ Resolved Identity ≠ Executable Search Anchor
18. Explicit Named Geographic Context > Device GPS Bias
19. GPS prior ≠ Named POI identity
20. Candidate Retrieval ≠ Entity Resolution
21. Pure Deictic Reference ≠ Qualified Reference
22. Static Detail ≠ Evidence-backed Subjective Fact
23. Semantic Similarity ≠ Business Eligibility
24. Evaluation 是发现系统性缺陷，不是让生产代码拟合 Dataset
```

---

# 三十三、面试官连续追问链

## 1. Working Memory 链

```text
为什么不用 History？
→ History 不是 current truth

为什么 Flat WM 不够？
→ 多 Task ghost inheritance

为什么 Task 后还要 same-turn snapshot？
→ 一个请求出现两个世界

为什么还要 OCC？
→ 跨请求并发写

慢 Tool 呢？
→ stale runtime result guard
```

## 2. Turn Semantics 链

```text
有 WM 为什么“这个日料咋样”还会错？
→ 状态有 truth，但没有写权限 authority

Extractor 不就是理解用户的吗？
→ 它提供 Delta 内容，不拥有 mutation permission

一句话多个动作怎么办？
→ TurnPlan：CriteriaIntent + ExecutionAction
```

## 3. Location 链

```text
为什么 Named Location 不能用 GPS 兜底？
→ intent/authority 不同

为什么“鼓楼”不能模型说了就算？
→ admin identity 必须 resolver/provider verify

为什么“福州大学”不能 substring 成福州市？
→ String Mention ≠ Entity Mention

为什么“师大”看 GPS？
→ GPS 只做 geographic context / disambiguation

为什么高德返回结果还不够？
→ Retrieval ≠ Resolution

为什么不 UNIVERSITY 硬过滤？
→ “福建师范大学附属小学”反例击穿 taxonomy gate
```

## 4. RAG / Tool 链

```text
为什么不纯向量？
→ semantic similarity 不能替代 hard business constraints

为什么不是全 MySQL？
→ 软语义需要 Embedding

为什么“重口吗”不能 detail？
→ subjective fact 需要 Review evidence

Tool 没证据怎么办？
→ 明确 uncertainty，不让 LLM 补事实
```

## 5. Evaluation 链

```text
为什么 Complete 不高？
→ 多 Contract AND

为什么还有失败就停？
→ 评测无系统性回归，继续修会 case chasing

怎么防过拟合？
→ holdout + 不改 Ground Truth + real HTTP smoke
```

---

# 三十四、明确不能吹的地方

面试时主动守住这些边界，可信度会更高：

```text
1. Task Identity 是餐饮业务启发式，不是通用 Task Manager。
2. TurnCommandSet 不是通用 Agent DSL。
3. Compound Turn 支持核心场景，不是任意复杂 Planner。
4. 当前 Full Snapshot 长期会增长，尚未做 compaction。
5. Provenance 还没有完整 sourceTurn/sourceEvent/timestamp/confidence/expiry。
6. 行政 registry 是 partial，需要 provider fallback。
7. POI Resolution 仍依赖地图 Provider 的召回质量，不是通用 Entity Linking 系统。
8. poiType/typeCode 只是辅助排序 metadata，不是自研完整 POI taxonomy。
9. 当前 hard constraint 不是全部 SQL 下推，仍有 Java 内存过滤。
10. semanticMinScore/TopK/weight 不是经过完整系统调参得到的“最优值”。
11. 当前没有 BM25 主链，不要说已经做 Hybrid BM25+Vector。
12. 当前不是 Event Sourcing。
13. 当前不是 Multi-Agent。
14. 当前不是 LangGraph equivalent。
15. Evaluation 仍有真实失败，不包装成生产级通用 Agent。
```

---

# 三十五、最后 10 分钟速记版

如果明天就面试，只背下面这些：

```text
项目定位：多轮消费决策 Agent，核心是 state consistency，不是 ChatBot。

History ≠ State：History 是语言证据，WM 是 current truth。

Flat WM → Task：解决 A→B→A ghost inheritance。

candidatePool → RecommendationBatch：解决历史 batch + ordinal reference。

Turn Semantics：Mention ≠ Mutation，Extractor 没有写状态权限。

same-turn + OCC + stale guard：分别解决单请求世界观、并发写、慢 Tool 旧结果。

Location：Explicit Named Context > GPS；Retrieval ≠ Resolution；canonical POI 后才成为 search anchor。

No-result：WAITING_RELAXATION + Recovery Command，不未经用户授权乱清条件。

Fact Tool：主观事实必须 evidence grounded，没证据就说不确定。

Retrieval：行政 SQL 粗筛 + Java hard filter + Milvus semantic recall + deterministic rerank。

Vector Update：durable sync task + stable documentId + revision + lease + retry。

Evaluation：402 单测全绿；Robustness 小幅提升；Holdout 不退化；只有一个 ambiguous probe 新回归。

停止开发原因：继续修已经出现 case chasing，Evaluation 用来找系统性缺陷，不是追求 Dataset 100%。
```

---

# 三十六、牛客面经趋势来源（用于确认复习方向，不需要另外阅读）

本文补充题参考了 2026 年牛客近期 Java 后端 / AI Agent / AI 应用面经中反复出现的主题，主要包括：Agent vs ChatBot、Memory、RAG/Rerank、Function Calling、幻觉、Tool 超时与重试、SSE、监控评测、模型选型、多 Agent、项目真实性、AI Coding ownership。

代表性帖子：

- Java 后端（AI Agent 方向）一面：Agent、SSE、RAG、Tool、监控、Memory、Rerank。
- 阿里/蚂蚁/字节 Agent 面经：多 Agent 编排、失败重试、安全、RAG 热更新。
- 字节 AI 全栈/AI 应用：项目核心链路、SSE、RAG、Memory、幻觉、API 超时。
- 快手 Java 二面：Agent 模块深挖、RAG、幻觉、Skill/Tool 和后端基础。
- 大疆 Agent：从零设计 Agent、Memory、外部 Tool 异常与容错。

复习策略不是背“100 道 Agent 八股”，而是优先把这些高频问题全部映射到本项目真实实现和真实 trade-off。

---

# 三十七、最终收口

> **这个项目真正的主线，是把一个最初依赖 History 和模型重解释的多轮系统，逐步收敛成 `Turn Semantics → Entity/Reference Grounding → Policy/FSM → Deterministic Reducer → Canonical Working Memory → Retrieval/Tool/DecisionSession → Evaluation`。真正有价值的不是堆了多少 Agent 名词，而是每次真实 Bad Case 都能定位到一个 authority leak、双真相或 representation gap，然后通过明确 Contract 收口；当修复开始变成 case chasing 时，又能用 Holdout 和多维评测判断应该停止。**
