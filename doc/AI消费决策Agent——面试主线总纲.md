# AI 消费决策 Agent——面试主线总纲

> 当前唯一面试主资料。后续 Working Memory、Task、Reference、Location、Turn Semantics、Decision Context、No-result Recovery、Evaluation 的新结论统一维护在本文，不再拆新的“补充”文档。
>
> 当前 Code Truth：main `c45c71ba0a69235aaf485ee4759baae8a878f616`
>
> 最近一次完整全量评测仍是 `709c615` 上的 Robustness Run139、Conversation-v1 Run140。之后 `5a03adf → 5fdc9e3 → ec2b4b4 → c45c71b` 主要完成 Mutation Authority、No-result Recovery、Relaxation→Canonical WM 投影和恢复语义边界的定向修复，尚未重新跑 full regression；Holdout 尚未执行。
>
> 本文的目的不是背类名，而是能完整回答：**遇到了什么真实问题 → 为什么原方案会失败 → 如何定位根因 → 有哪些方案 → 为什么选现在的方案 → 如何实现 → 如何验证 → 新方案又暴露了什么边界。**

---

# 一、先把整个项目讲明白

## 1. 30 秒版本

我做的是一个本地生活餐饮消费决策 Agent，重点不是“调用大模型推荐餐厅”，而是多轮决策状态管理。用户会连续修改地点、预算、菜系，会切换多个方案，会问“最开始第二家”“为什么推荐这个”“我什么时候说过安静”“那附近有啥”。如果只把聊天历史塞给模型，状态很容易串、旧条件会幽灵继承、历史引用会漂移。

所以项目后来逐步演化成：`Task-scoped Working Memory + RecommendationBatch + Reference Resolver + TurnPlan + OCC + Canonical Turn Semantics + Location Authority + Decision Context Query + Evaluation`。核心思路是：**模型负责开放语言理解，确定性代码负责业务状态和 authority。**

---

## 2. 2 分钟版本

入口是一条显式 Pipeline：

```text
Bootstrap
→ Context Rewrite / Enrichment
→ Intent Routing
→ Criteria Reduction
→ Policy Guard
→ Execution
```

最早系统没有 canonical Working Memory，状态散在聊天历史、当前 Context、candidatePool、focusedShop 等地方。随着真实多轮测试，依次暴露了：

```text
历史候选被覆盖
→ RecommendationBatch

A→B→A 方案串状态
→ Task Scope

同一请求重新 load 旧状态
→ same-turn authoritative snapshot

并发旧请求覆盖新状态
→ OCC + stale runtime result guard

“第一家太贵，第二家有插座吗”无法同时表达
→ TurnPlan

GPS、命名地点、行政区、POI 混在一起
→ Location Contract + Resolver Authority

“为什么推荐”“我什么时候说过”靠模型重猜历史
→ Decision Context Query + Provenance + Historical Decision Fact

“这个日料咋样”把日料写进搜索条件
→ Canonical Turn Semantics

WAITING_RELAXATION 下“那附近有啥”无法恢复，而且解释不清刚才搜了什么
→ BROADEN_FOOD_SCOPE + Failure Explanation + Relaxation Command Projection
```

最终形成两条核心 authority：

```text
Canonical Working Memory
回答：系统现在相信什么？

Canonical Turn Semantics
回答：用户这一轮允许系统做什么？
```

---

## 3. 5 分钟版本怎么铺

不要按“技术栈”讲，按事故演进讲：

```text
1. 没有 canonical state
2. candidatePool / shownPool / RecommendationBatch
3. Flat WM → Task Scope
4. same-turn snapshot
5. OCC / stale runtime result
6. Reference / TurnPlan
7. Location Contract / Administrative Authority
8. Decision Context / Provenance / Historical Fact
9. Canonical Turn Semantics
10. No-result Recovery / Failure Explainability
11. Evaluation 如何推动每一轮架构变化
```

面试官听完应该能感受到：这个项目不是“一开始画好架构再实现”，而是每次真实失败都推动一个更准确的不变量。

---

# 二、当前系统里几个最容易混淆的概念

## 1. Chat History、Context、Working Memory、DecisionSession 分别是什么

```text
Chat History
= 用户和系统说过什么

ChatProcessingContext
= 当前 Turn 运行时临时数据

ConversationWorkingMemory
= 当前会话 canonical business state

DecisionSession
= 某一次决策执行的输入、结果和历史事实快照

RecommendationBatch
= 某一轮推荐候选的身份/顺序指针
```

最重要的区分：

> “历史里说过”不等于“当前仍生效”；“DecisionSession 当时怎么执行”也不等于“当前 Working Memory 现在是什么”。

OpenAI Agents SDK 的 Session 更接近“持久化 conversation items”；LangGraph checkpoint 更接近“thread state snapshot”；而本项目的 Working Memory 是业务特化状态，不是单纯消息历史。

---

## 2. 为什么不能只用聊天历史

例如：

```text
预算100
→ 算了预算不限
→ 还是100
```

History 只能证明三句话都说过，不能天然给出“当前预算=100”。如果每轮让 LLM 重读历史决定当前状态，会出现：

```text
旧值和新值竞争
清除语义不稳定
长上下文成本上涨
多方案之间串状态
并发没有 version
历史 ordinal reference 不稳定
```

所以：

```text
History = Language Evidence
Working Memory = Current Business Truth
```

---

# 三、演进循环 1：candidatePool 为什么不够

## 真实现象

第一次推荐：

```text
A B C
```

用户说“换一批”，得到：

```text
D E F
```

这时系统既要知道：

```text
当前可继续操作的是 D/E/F
```

又要知道：

```text
A/B/C 已经展示过，不能无意重复
```

## 第一版思路

拆成：

```text
candidatePool = 当前候选
shownPool = 历史展示过的商户
```

## 为什么还失败

用户问：

```text
最开始第二家怎么样？
```

`shownPool=[A,B,C,D,E,F]` 只能知道“看过谁”，不能恢复：

```text
第一批第二家=B
第二批第一家=D
```

## 根因

我们丢了“候选出现在哪一轮、顺序是什么”的历史结构。

## 最终方案

新增：

```text
RecommendationBatch
├─ decisionSessionId
└─ candidates[]
```

当前 Candidate Projection 由 latest batch 推导；历史 shown union 从所有 batch 推导。

## 为什么不是把所有详细信息复制进 Batch

Batch 只保存 identity/index/pointer，完整 `matchedReasons/evidence` 放在 `AiDecisionSession.resultJson`。否则每个 Working Memory snapshot 都会重复复制评论证据，既膨胀又形成两个历史事实源。

## 面试追问

**Q：为什么不是 Event Sourcing？**

不是。RecommendationBatch 是历史推荐索引，当前状态仍然靠 versioned snapshot；事件主要做审计，不通过 replay 重建整个当前状态。

---

# 四、演进循环 2：Flat Working Memory 为什么会 Ghost Inheritance

## 真实现象

用户：

```text
福州火锅，人均100
→ 换杭州西湖日料，人均300
→ 还是最开始那套
```

Flat WM 只有一套 criteria，第二套覆盖第一套后，再恢复容易拼成：

```text
福州 + 火锅 + 西湖 + 300
```

## 第一版思路

继续在一套 criteria 上做 replace/clear。

## 为什么失败

这里不是单字段更新，而是“用户在同时考虑多个方案”。一套 flat state 无法表达多个并存方案。

## 根因

Conversation Scope 和 Decision Task Scope 混在了一起。

## 最终方案

```text
ConversationWorkingMemory
└─ tasks[]
   ├─ Task A
   │  └─ 福州/火锅/100
   └─ Task B
      └─ 杭州/日料/300
```

恢复 A 是 re-activate Task A，不是重新拿当前状态拼 A。

## Task Identity 为什么不包含预算

预算通常是 refinement，不代表一个新需求身份。否则：

```text
福州火锅100
福州火锅80
福州火锅120
```

会碎成三个 Task。

当前 Task Identity 仍是业务启发式，不要吹成通用任务管理算法。

---

# 五、演进循环 3：Task V2 单测过了，E2E 为什么还错

## 真实现象

Task 切换逻辑单测正确，但 E2E 中新 Task 又被旧状态覆盖。

## 定位过程

发现同一个请求中：

```text
transitionTask()
→ 修改 request-scoped WM

reduceCriteria()
→ 又从 DB reload 旧 version WM
```

## 根因

同一 Turn 内出现了两个不同的世界视图。

## 最终不变量

> 一个 Turn 只能有一个 authoritative Working Memory Snapshot。

```text
bootstrap snapshot
→ task transition
→ merge
→ reduce
→ persist
```

必须基于同一份 request-scoped memory。

## 和 OCC 有什么区别

```text
same-turn snapshot consistency
= 一个请求内部不同 Node 不能看不同版本

OCC
= 不同请求之间不能让旧请求覆盖新请求
```

面试一句话：

> 同一 Turn 看同一个世界；并发 Turn 不允许旧世界覆盖新世界。

---

# 六、演进循环 4：OCC 还不够，慢 Tool 为什么仍可能写旧状态

## 真实现象

```text
Turn A 在 v20 启动 Tool
Turn B 很快把 WM 更新到 v21
Turn A 5 秒后返回
```

如果 A 直接把 Tool Result 应用回去，会基于旧世界污染 v21。

## 第一版方案

只在 Working Memory append 时做 `expectedVersion` 检查。

## 为什么不够

Tool 调用期间状态已经变化，旧 runtime result 即使最终写入过程有控制，也可能在业务层先被当成有效结果。

## 最终方案

`AgentSessionContext` 保存：

```text
baseWorkingMemoryVersion
```

回写时重新读取 latest version：

```text
base != latest
→ STALE_RUNTIME_RESULT
→ reject
```

## 持久化为什么是 Full Snapshot

当前状态规模可控，Full Snapshot 带来：

```text
读取简单
恢复简单
debug 简单
历史版本直接查看
```

代价是长会话存储增长。未来才考虑 periodic full + delta / compaction，不提前做 Event Sourcing。

---

# 七、演进循环 5：Reference 和 TurnPlan 为什么要拆

## 真实现象 1：ordinal reference 漂移

```text
第一家
第二家
刚才那个
最开始第二家
```

如果每个 Handler 自己解析“第一家”，就会出现多套 ordinal 语义。

## 方案

```text
Natural Language
→ ReferenceIntent
→ BatchAwareReferenceResolver
→ ResolvedShopReference
```

Reference 只有一个 authority。

## 真实现象 2：一句话同时修改状态和问事实

```text
第一家太贵了，第二家有插座吗？
```

如果一个 Turn 只有一个 Action：

```text
START_DECISION
```

会吞掉商户事实查询；如果只选：

```text
BUSINESS_FOLLOW_UP
```

又会吞掉“太贵”的 criteria mutation。

## 根因

Criteria Mutation 和 Execution Action 是正交维度，却被塞进一个单标签 route。

## 最终方案

```text
TurnPlan
├─ CriteriaIntent: NONE / APPLY_DELTA
└─ ExecutionAction
```

## 为什么 Reference 必须 pre-mutation resolve

用户说“第二家”时看到的是 mutation 前的 batch。若先修改 criteria 导致 candidate invalidation，再解析“第二家”，引用会漂移。

因此：

```text
Reference belongs to pre-mutation snapshot
```

---

# 八、演进循环 6：Location 不是一个字符串

这条线最值得面试讲，因为它连续暴露了不同层级的“语义混用”。

## 第一轮：设备 GPS 和命名目的地混用

用户明确说“重庆”，系统却可能继续复用之前福州 GPS。

根因：

```text
Location Value
和
Location Intent / Provenance
```

混在一起。

于是区分：

```text
CURRENT_DEVICE
EXPLICIT_TARGET
```

并明确：只有相对当前位置表达才需要 GPS。

---

## 第二轮：行政层级丢失

`福建省` 被当成 city，SQL projection 错。

根因：

```text
Location Value
≠ Administrative Level
```

于是 canonical criteria 增加：

```text
targetProvince
targetCity
targetDistrict
targetArea
```

`targetArea` 保留给 POI/商圈/地标，不再拿来装区县。

---

## 第三轮：LLM fallback 丢行政实体

真实场景：

```text
鼓楼有什么东西吃
```

模型可能 `tool_calls=[]`，行政信息没进入 canonical state。

第一反应如果继续加 prompt 不够稳，因为行政区本质是 closed-world entity。

最终引入：

```text
AdministrativeRegionResolver
→ local registry
→ AMap District WebService fallback
```

模型可以给 candidate hint，但 Resolver 才拥有 administrative identity authority。

---

## 第四轮：partial registry false uniqueness

本地只有一个“鼓楼区”不代表全国只有一个鼓楼区。

核心结论：

```text
Partial Dataset ≠ Complete World
```

裸区县没有 parent 时，不能因为本地唯一就直接执行；要么有 parent context，要么 provider 给可信 hierarchy，否则澄清。

---

## 第五轮：Administrative Authority Leak

即使已经有 Resolver，旧流程仍可能：

```text
Resolver(raw query) = NOT_FOUND
Model targetDistrict=连江县
merge NOT_FOUND 什么都不做
→ model field 偷渡进 canonical state
```

这说明：

> “有一个 Resolver 类”不等于它真的拥有 authority；所有旁路都必须不能绕过它。

于是模型行政字段降级为 untrusted hint：

```text
LLM candidate hint
→ raw-query grounding
→ Administrative Resolver validation
→ verified identity
→ canonical state
```

---

## 第六轮：Substring Grounding 也会错

```text
福州大学附近
```

包含“福州”，但不代表用户明确设置 `targetCity=福州市`。

因此：

```text
String Mention ≠ Entity Mention
```

最终 `resolveHint()` 统一处理 raw-query grounding，并复用 POI boundary；显式“福州市福州大学附近”允许 city + POI 共存。

---

## 第七轮：POI Mention ≠ Executable Search Anchor

真实问题：

```text
师大附近有没有啥好吃的
```

旧系统会写：

```text
targetArea=师大
```

但 POI 没 resolve，后面又偷偷复用旧 Browser GPS 搜索。

根因：

```text
Location Mention
≠ Resolved Location Identity
≠ Executable Search Anchor
```

最终 contract：

```text
Administrative scope
→ AdministrativeRegionResolver

POI / Landmark / Business Area
→ maps_geo / LocationResolutionProvider

CURRENT_DEVICE
→ Browser GPS
```

POI 未解析成功必须 clarification，不能 silent fallback 到旧 GPS。

---

# 九、演进循环 7：为什么“我说过安静吗”不能靠 History 回答

## 真实问题

用户问：

```text
为什么推荐第一家？
我前面有说过安静吗？
你的过滤条件是什么？
你刚才查的是哪里？
```

这些不是新推荐，也不是外部商户事实查询，而是在读“已有决策状态”。

## 第一版思路

让 GENERAL_CHAT 读 History 自由回答。

## 为什么失败

模型会重新解释历史，形成第二套真相。例如当前 criteria 有 `安静`，并不证明用户明确说过安静；它可能是从“适合聊天”派生出的。

## 最终方案

顶层统一：

```text
DECISION_CONTEXT_QUERY
```

内部 QueryType：

```text
WHY_RECOMMENDED
CONSTRAINT_PROVENANCE
CURRENT_CRITERIA
EXECUTED_SEARCH_SCOPE
```

严格 read-only，不 reduce、不切 Task、不 invalidate、不改 focus、不增加 WM version。

---

## Provenance 为什么要 element-level

不能写：

```text
preferences → USER_EXPLICIT
```

因为同一个 list 里：

```text
安静 → DERIVED
约会 → USER_EXPLICIT
不排队 → USER_EXPLICIT
```

因此使用：

```text
preference:安静
preference:约会
```

当前来源：

```text
USER_EXPLICIT
DERIVED
SYSTEM_DEFAULT
```

来源在 Extractor / Merger 阶段确定，StateService 不重新做 NLU。

---

## 为什么历史推荐解释必须读历史 DecisionSession

如果第一轮预算150，后来改成80，再问：

```text
为什么最开始第一家被推荐？
```

不能拿当前 budget=80 重新解释历史结果。

正确：

```text
ResolvedShopReference
→ RecommendationBatch.decisionSessionId
→ AiDecisionSession.resultJson
→ 当时 matchedReasons/evidence
```

这避免 Temporal Leakage。

---

# 十、演进循环 8：有 Canonical Working Memory，为什么系统还是会胡说

这是当前项目最重要的新阶段。

## 真实 Bad Case

```text
这个日料咋样
```

用户只是问当前推荐商户，但旧 Extractor 抽到：

```text
cuisine=日料
```

随后旧逻辑：

```text
hasMutation(criteriaDelta)=true
→ APPLY_DELTA
→ 日料被写入 canonical criteria
```

另一个 Case：

```text
我附近呢？除了东北菜应该都OK
```

Context Rewrite 看到“我附近”提前 return：

```text
在当前设备附近搜索餐饮商户
```

“除了东北菜”被完全丢掉。

## 第一版误判

一开始看起来像：

```text
cuisine provenance 不完整
Context Rewrite 少处理一个 compound phrase
```

如果按这个方向继续修，会不断加 contains/regex。

## 真正根因

虽然已经有 canonical state，但没有 canonical turn semantics。

同一句话被：

```text
Rewrite
Routing
ConstraintExtractor
Reference
DecisionContextQuery
```

多次独立理解。

于是形成：

```text
Mention 被当成 Mutation
Rewritten Query 被当成 Whole-turn Truth
```

## 业内对照

Rasa CALM 当前就是“用户消息 → 一组高层 commands → deterministic Dialogue Manager”。它特别强调一条消息可以同时产生多个 command，比如既回答当前问题又提出新请求。这比“一个 intent label”更适合 compound turn。

参考：

- https://rasa.com/docs/pro/customize/command-generator/
- https://rasa.com/docs/learn/concepts/calm/

## 最终方案

新增 request-scoped：

```text
TurnCommandSet
TurnCommand
TurnUnderstandingService
```

它不保存业务事实，只回答：

```text
这一轮用户允许系统做什么？
```

当前 command 只覆盖真实需求：

```text
SET_CONSTRAINT
CLEAR_CONSTRAINT
EXCLUDE_CONSTRAINT
SET_LOCATION_INTENT
ASK_DECISION_CONTEXT
ASK_SHOP_FACT
REFERENCE
BROADEN_FOOD_SCOPE
```

不要把它说成通用 DSL。

---

## 最关键职责拆分

```text
TurnUnderstanding
→ 决定“能不能改”

ConstraintExtractor
→ 在允许修改后决定“具体改什么”

CriteriaMerger / ConversationStateService
→ 决定“怎么落状态”
```

核心不变量：

```text
Mention ≠ Mutation
Fact Question ≠ State Update
Original Turn ≠ Rewritten Query
```

---

## 为什么 Context Rewrite 要降级

现在：

```text
Original Message
= Turn Semantics Source

Context Rewrite
= reference / ellipsis enrichment

Retrieval Query Rewrite
= consumer-specific derived representation
```

Rewrite 不再有资格覆盖整轮业务语义。

---

## 为什么新增 excludedCuisines

```text
除了东北菜都可以
```

旧 schema 只有一个正向 cuisine，无法表达：

```text
ANY cuisine EXCEPT 东北菜
```

所以增加 durable hard constraint：

```text
excludedCuisines[]
```

它进入 hard filter，并被纳入 candidate-universe invalidation。

---

# 十一、演进循环 9：Turn Semantics 第一版为什么还没真正收口

## full regression 暴露的问题

第一版已经让 `selectAction()` 使用：

```text
TurnUnderstandingService.shouldApplyReferenceMutation()
```

但 `route()` 里还有旧旁路：

```text
isCompoundMutationFollowUp()
→ ensureCriteriaDelta()
→ hasMutation(criteriaDelta)
```

于是出现两个 Mutation Authority。

真实结果：

```text
第一家那个日本料理环境怎么样？
```

仍可能因为 Extractor 抽到 cuisine=日料，被当成 mutation。

## 根因

> 新增一个 authority class 不代表 authority boundary 已经成立；旧旁路必须全部失去决定权。

## 最终修复

`5a03adf`：

```text
route()
→ hasStructuredReferenceMutation()
→ TurnUnderstandingService

selectAction()
→ 同一个 helper
→ YES 后才 ensureCriteriaDelta()
```

旧 `hasMutation(DecisionConstraints)` 删除。

缺少 TurnUnderstandingService 时 reference mutation fail-closed。

## 面试一句话

> Extractor 提供 Delta 内容，但不拥有 State Write Permission。

---

# 十二、演进循环 10：WAITING_RELAXATION 为什么用户会觉得系统“在吹牛”

## 真实对话

用户：

```text
附近兰州拉面
→ 提供 GPS
→ 3/5km 都没有结果
→ “没有兰州拉面吗”
→ “什么意思”
→ “我就要吃兰州拉面”
→ 仍然 0
→ “无敌了，那附近有啥”
```

旧系统最后把：

```text
无敌了，那附近有啥
```

路由成 GENERAL_CHAT。

并且无结果卡片只说：

```text
当前条件下没有找到匹配商户，可以扩大范围……
```

用户不知道：

```text
到底搜了哪里？
多大半径？
保留了兰州拉面吗？
预算还在吗？
为什么一直让我放宽？
```

## 第一版判断

看起来像“附近有啥没有命中餐饮 route”。

如果只加：

```text
contains("附近有啥") → START_DECISION
```

还是不够，因为旧 canonical state 里仍然保留：

```text
keyword=兰州拉面
cuisine=面食
```

重新搜索仍可能继续是 0。

## 根因

这里缺的不是 Route，而是两个 contract：

```text
No-result Recovery Command Contract
+
Failure Explanation Contract
```

## 最终恢复命令

新增：

```text
BROADEN_FOOD_SCOPE
```

只在：

```text
WAITING_RELAXATION
+
当前仍有具体 keyword/cuisine
+
用户表达泛化搜索
```

时合法。

语义：

```text
clear keyword
clear cuisine
保留 location / GPS
保留 radius
保留 budget
保留其他明确 preferences
保留历史 RecommendationBatch
→ retry search
```

普通非暂停态“附近有啥”不会触发这个 recovery command。

---

## 为什么“附近有什么兰州拉面”不能触发 BROADEN

用户仍然坚持具体 target。

因此 TurnUnderstanding 区分：

```text
强放弃信号：随便 / 都行 / 不一定 / 不限 / 其他……
弱泛化信号：啥 / 什么
```

弱泛化只有在用户没有再次提及当前 keyword/cuisine 时才 broadening。

随后还发现一个典型代码边界：空字符串 target 会让 `text.contains("")` 恒 true，于是新增 `containsNonBlankTarget()`，只检查非 blank 的 paused target。

这个小 Bug 很适合面试讲“为什么 targeted test 需要覆盖 schema 组合，而不是只覆盖一条真实句子”。

---

# 十三、演进循环 11：No-result Recovery 又暴露 DecisionSession / WM 双真相

## 新问题

`continueDecision()` 在 `DecisionSession.constraints` 里执行：

```text
EXPAND_RADIUS
INCREASE_BUDGET
RELAX_CUISINE
BROADEN_FOOD_SCOPE
...
```

但 `snapshotDecision()` 按设计不覆盖 `activeTask.criteria`。

所以可能出现：

```text
DecisionSession：keyword/cuisine 已清空，正在泛搜
Working Memory：仍然是 兰州拉面/面食
```

## 为什么不能简单让 snapshotDecision 覆盖 WM

因为我们之前已经建立不变量：

> Execution result 不是 canonical state writer。

如果把整份 execution constraints 覆盖回 WM，会重新打开很多旁路：模型/执行层产生的临时值也可能偷渡进 canonical state。

## 最终方案

新增：

```text
ConversationStateService.applyDecisionRelaxationCommand()
```

只有已经被 FSM 验证过的 relaxation command，按白名单字段投影回 canonical criteria。

例如：

```text
BROADEN_FOOD_SCOPE
→ clear keyword/cuisine

RELAX_CUISINE
→ clear cuisine

EXPAND_RADIUS
→ sync successful radius

INCREASE_BUDGET
→ sync successful budget

RELAX_QUIET / ALLOW_QUEUE / RELAX_LIGHT_TASTE
→ remove 对应 preference + provenance
```

每次放宽会 invalidate 当前 candidate/focus projection，但历史 RecommendationBatch 不删除。

`snapshotDecision()` 继续只负责：

```text
phase
batch
focus
```

不覆盖 criteria。

## 最终职责

```text
Turn Semantics
→ 用户发了什么 command

Decision FSM
→ command 现在是否合法

Canonical WM Reducer / Command Projection
→ command 如何改变当前业务事实

DecisionSession
→ 记录这次执行事实
```

这四层是当前最值得背熟的架构关系。

---

# 十四、演进循环 12：失败解释为什么也要有单一事实源

## 旧问题

首次进入 WAITING_RELAXATION 的卡片是一套 generic 文案；用户再问“什么意思”时，`EXPLAIN_SUSPENDED_DECISION` 又由另一套代码拼文案。

风险：

```text
首次说 5km
后续解释说 7km

首次只提 budget
后续又提 cuisine
```

## 根因

Failure Explanation 也出现双 truth。

## 最终方案

新增：

```text
DecisionFailureExplanationFormatter
```

输入 DecisionResponse 的持久化事实：

```text
constraints
RelaxationInfo
options
recommendations
```

首次 WAITING 和后续 EXPLAIN 共用同一个 formatter。

当前输出至少包括：

```text
搜索范围/半径
keyword/cuisine/budget/preferences
结果数量
是否自动扩大过默认半径
当前可以执行的下一步
```

这不是“美化文案”，而是 Failure Explainability：

> 系统失败后，用户必须知道失败发生在什么条件下，以及接下来哪些 recovery action 是真实可执行的。

---

# 十五、当前数据结构与职责地图

## ConversationWorkingMemory

```text
schemaVersion
activeDecisionSessionId
lastDecisionSessionId
deviceLocation
pendingLocationCandidates
activeTaskId
tasks[]
focusedShopId / focusedShopName
dialogPhase
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

## DecisionConstraints

当前主要包含：

```text
targetProvince / targetCity / targetDistrict / targetArea
locationIntent
nearby / radiusKm
budgetPerPerson
cuisine
excludedCuisines
keyword
preferences
arrivalTime
...
```

## RecommendationBatch

```text
decisionSessionId
candidate refs
```

## TurnCommandSet

request-scoped，不 durable：

```text
本轮允许做什么
```

## DecisionSession

历史执行事实：

```text
constraintsJson
requestContextJson
resultJson
status
```

---

# 十六、业内方案怎么对照，不要乱吹

## OpenAI Agents SDK

官方 Session 主要负责跨 run 持久化 conversation items；Context 又区分 runtime/local context 和 LLM-visible context。

这支持我们的一个核心判断：

```text
Session History
≠
Business Canonical State
```

参考：

- https://openai.github.io/openai-agents-js/guides/sessions/
- https://openai.github.io/openai-agents-js/guides/context/

## LangGraph

LangGraph 的 thread/checkpoint 适合通用 graph state persistence、resume、time-travel、human-in-the-loop。

可以类比：

```text
thread_id ≈ chatId
checkpoint ≈ versioned state snapshot
```

但本项目没有恢复任意 graph node 执行位置，所以不要说“等价 LangGraph”。

## Rasa CALM

对 Turn Semantics 最有参考价值：

```text
User Message
→ Command Generator
→ high-level commands
→ deterministic Dialogue Manager
```

它允许一条用户消息生成多个 commands，比 single-intent label 更适合 compound turns。

参考：

- https://rasa.com/docs/pro/customize/command-generator/
- https://rasa.com/docs/learn/concepts/dialogue-management/

---

# 十七、Evaluation：项目为什么不是靠“感觉修 Bug”

## 当前最新 full regression 口径

注意：以下是 `709c615`，不是当前 `c45c71b`。

### Robustness Run139

```text
Cases: 48
Complete: 20/48
Route: 44/48
Tool: 47/48
Final Status: 37/48
Locality: 47/48
Context Rewrite: 7/7
```

### Conversation-v1 Run140

```text
Cases: 40
Complete: 28/40
Route: 37/40
Tool: 32/40
Final Status: 40/40
Locality: 40/40
```

之后最新代码只跑了定向 tests，Holdout 尚未重新执行。

---

## 为什么不能只看 Complete

Agent 评测要拆：

```text
Route
Tool
Final Status
Locality
Working Memory projection
session relation
Context Rewrite
```

Run139 中就存在旧 Dataset Contract 与当前安全语义不一致，例如裸区县旧 Ground Truth 希望直接执行，但当前 partial registry 安全 contract 要求 clarification。

同时也不能反过来说“Complete 低全是 Dataset 错”，因为 Compound Intent、Task 恢复、Location Clarification 等仍有真实债务。

正确表达：

> 聚合 Complete 是多 contract 的结果，要看失败聚类；我不会为了提升一个数字去把生产代码迁就旧 Ground Truth。

---

## 为什么 targeted + full + holdout 分层

```text
小修
→ Unit + 3~8 targeted

3~5 个相关小修 / 阶段闭环
→ full robustness

WM 阶段闭环
→ robustness + conversation-v1

最终归档
→ robustness + v1 + holdout
```

原因：

```text
反馈速度
成本
模型波动
防止围着 Dataset 过拟合
```

牛客近期 Agent 评测面经也常追：Outcome vs Trajectory、工具选择、最终状态、线上失败样本回流和冻结回归集。

参考：

- https://www.nowcoder.com/discuss/916878230575906816

---

# 十八、牛客高频追问：按这个项目怎么答

## Q1：State、Context、Memory 区别？

State 是当前应用事实；Context 是本次调用真正提供给模型/工具的信息；Memory 是为了未来 Turn 或未来会话保留的信息。数据库里“有”不代表本轮模型“看见”。

项目对应：

```text
WM = State
ChatProcessingContext = Runtime Context
History / retained facts = Memory evidence
```

牛客相关：

- https://www.nowcoder.com/discuss/916877706187276288

---

## Q2：为什么不用 LangGraph？

当前 Java/Spring Boot 主流程高度确定，真正复杂的是业务 state invariants，而不是 graph 编排。自己持有 reducer、OCC、Task、Reference 更直接。若未来变成长时任务、人工审批、跨小时中断恢复，再考虑通用 durable workflow runtime。

---

## Q3：为什么不是把整个 History 给模型？

因为 History 是证据，不是当前 truth；同时会增加 token、延迟和注意力污染。真正当前状态应结构化，历史事实按需回查。

---

## Q4：Working Memory 会不会越来越大？

会。当前 Full Snapshot 是小规模场景的 trade-off。已经避免把 evidence 大文本复制进 RecommendationBatch；未来才考虑 archive / periodic full + delta / compaction。

---

## Q5：Memory 冲突怎么处理？

当前有来源：

```text
USER_EXPLICIT > DERIVED / SYSTEM_DEFAULT
```

但还没有完整 timestamp/confidence/expiry 冲突模型，这是当前边界。

---

## Q6：Agent 怎么避免状态幻觉？

不是靠 prompt 写“不要胡说”，而是把 authority 拆开：

```text
行政 identity → Resolver
reference → ReferenceResolver
当前 criteria → WM
历史推荐事实 → DecisionSession.resultJson
mutation permission → TurnUnderstanding
```

LLM 可以提出 candidate，但不能直接成为业务真相。

---

## Q7：为什么一个大模型不能把 Routing、Memory、Tool 都做了？

开放语言理解适合模型；OCC、candidate invalidation、行政 identity、historical reference、command legality 都有确定性不变量，交给代码更稳定。

---

## Q8：Workflow 和 Agent 怎么区分？

本项目是 hybrid：

```text
固定业务阶段 → Pipeline / Workflow
开放语义 → LLM
外部事实 → Tool
状态安全 → deterministic reducer / FSM
```

不是为了“Agent”这个名字把所有步骤交给模型。

---

## Q9：如果 Tool 超时后状态已经变化怎么办？

用 baseWorkingMemoryVersion 检查 stale runtime result；旧结果不能覆盖新状态。

---

## Q10：为什么 Reference 要提前解析？

因为 mutation 后 candidate universe 可能变化。“第二家”属于用户发话时刻的 pre-mutation snapshot。

---

## Q11：为什么“这个日料咋样”不能写 cuisine？

这是 Mention 与 Dialogue Act 的区别。出现 entity 不代表执行 SET slot；只有 Turn Semantics 给 mutation permission 后，Extractor 的字段才有资格进入 reducer。

---

## Q12：为什么“福州大学”不能识别成福州市？

Substring grounding 不等于 entity grounding。行政 identity 要通过 Resolver 校验；POI 中包含行政 alias 不能偷渡成 admin scope。

---

## Q13：为什么用户问“你刚才查哪”要看 DecisionSession，不看当前 WM？

用户问的是历史 execution fact，当前 WM 可能已经变化。读当前 WM 会发生 Temporal Leakage。

---

## Q14：系统没搜到时怎么恢复？

不能只返回“没结果”。需要：

```text
Failure Explanation
+
Legal Recovery Commands
```

例如 `BROADEN_FOOD_SCOPE` 清具体 food target，但保留 location/radius/budget，再 retry。

---

## Q15：怎么防过拟合？

生产代码禁止：

```text
CaseCode 特判
shopId 特判
具体地名/商户 hardcode
围着单句不断 contains
```

优先把 Bad Case 提升成不变量，再用 targeted + full + holdout 验证。

---

# 十九、现在最值得背的 16 个不变量

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
13. Location Mention ≠ Executable Search Anchor
14. Mention ≠ Mutation
15. DecisionSession Execution Fact ≠ Canonical Current Criteria
16. Failure State 必须有可解释事实 + 可执行 Recovery Command
```

---

# 二十、当前明确不能吹的地方

```text
Task Identity 仍是业务启发式
Compound Intent 还不是任意多动作 planner
部分历史 Task Reference 仍有 NLP 下沉债务
Full Snapshot 长期会增长
不是 Durable Workflow Runtime
Provenance 暂无完整 sourceTurn/sourceEvent/timestamp/confidence/expiry
行政 registry 是 partial，需要 provider fallback
最新 c45c71b 尚未跑 full robustness/v1
Holdout 尚未跑
```

面试里主动说边界比硬包装更可信。

---

# 二十一、面试官连续追问链

## 链 1：为什么做 Working Memory

```text
为什么不用 History？
→ History 不是当前 truth

为什么 Flat WM 不够？
→ 多 Task Ghost Inheritance

为什么 Task 后还要 Snapshot？
→ same-turn stale reload

为什么还要 OCC？
→ 跨请求并发

Tool 慢返回怎么办？
→ stale runtime result guard
```

## 链 2：为什么做 Turn Semantics

```text
有 WM 为什么还会错？
→ 写入前语义 authority 仍多头

“日料”为什么不能直接写 cuisine？
→ Mention ≠ Mutation

Extractor 不就是理解用户的吗？
→ 它负责 Delta 内容，不负责写权限

为什么还要 TurnPlan？
→ mutation / execution 正交

业内有类似设计吗？
→ Rasa CALM command generator + dialogue manager
```

## 链 3：Location

```text
为什么不用 LLM 直接抽地点？
→ admin closed-world identity

本地唯一为什么不能直接用？
→ partial dataset ≠ complete world

为什么连江不自动 GPS？
→ named destination ≠ CURRENT_DEVICE

师大为什么不能直接搜？
→ mention ≠ resolved anchor
```

## 链 4：No-result

```text
没结果为什么不自动放宽？
→ 系统不能静默修改用户硬条件

那用户说“附近随便看看”怎么办？
→ explicit recovery command

为什么不直接改 DecisionSession 就完了？
→ WM 才是 canonical current state

为什么第一次就要解释范围？
→ Failure Explanation 必须基于事实，可恢复而不是让用户猜
```

---

# 二十二、最后用一句话收口

> **这个项目真正的主线不是“做了一个餐饮 Agent”，而是把一个最初依赖聊天历史和模型重解释的多轮系统，逐步收敛成“Turn Command → Grounding/Authority → Deterministic Reducer/FSM → Canonical Working Memory → Tool/DecisionSession → Evaluation”的状态系统。每次架构变化都不是为了加组件，而是因为真实对话暴露了一个新的双真相或 authority 泄漏。**
