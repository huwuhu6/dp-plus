# AI 消费决策 Agent——Working Memory 主线补充：Canonical Turn Semantics

> 当前 Code Truth：main `5a03adf5401d4f79fa663f2246913d2c2d2c7cbd`
>
> 最近一次全量评测基线：`709c615` 上的 Robustness Run139、Conversation-v1 Run140；`5a03adf` 仅完成定向 Unit Tests，尚未重新跑 full regression，Holdout 仍未执行。
>
> 本文是 `Working Memory主线面试资料 v2` 的最新专题补充，重点回答：为什么已经有 Canonical Working Memory 后，系统仍会“越聊越胡说”；为什么需要再建立 Canonical Turn Semantics；以及最终如何把“是否允许改状态”“具体改什么”“怎么持久化”拆成三个不同 authority。

---

# 一、先给结论：Canonical State 解决不了所有多轮问题

Working Memory 解决的是：

```text
系统现在相信什么？
```

但真实多轮对话继续暴露另一类问题：

```text
用户这一轮到底做了什么？
```

例如：

```text
这个日料咋样？
```

用户只是在问当前推荐商户，但旧系统可能因为抽取到了 `cuisine=日料`，就把它写成新的搜索约束。

又例如：

```text
我附近呢？除了东北菜应该都OK
```

旧 Context Rewrite 只保留“当前设备附近搜索”，把“排除东北菜”整段丢掉。

因此项目出现第二阶段问题：

```text
Canonical Working Memory 已经存在
但 Canonical Turn Interpretation 不存在
```

同一句用户输入会被：

```text
Context Rewrite
Routing
ConstraintExtractor
Reference Resolver
Decision Context Query
```

多次独立解释，而且不同模块有时看 original message，有时看 rewritten message。

最终形成“状态本身有版本、有 authority，但写状态前的语义判断仍然多头管理”。

---

# 二、为什么这不是几个普通 Bug，而是一个抽象缺失

真实 Bad Case：

```text
1. “这个日料咋样”
   → 事实追问被写成 cuisine mutation

2. “师大附近有没有啥好吃的”
   → targetArea=师大 已进入状态
   → 但 POI 没有 resolve
   → 旧 GPS 却被继续复用

3. “你的过滤条件是什么”
   → 顶层 route 对了
   → QueryType 仍靠窄 contains 判定，无法稳定识别 CURRENT_CRITERIA

4. “我附近呢？除了东北菜应该都OK”
   → Rewrite 提前 return
   → 只留下 CURRENT_DEVICE
   → 排除条件丢失
```

表面上四个模块各有问题，但共同根因是：

> 一条自然语言没有先被归约成“本轮结构化动作”，而是让多个下游组件分别重新猜。

因此真正的不变量不是“再补几个关键词”，而是：

```text
Mention ≠ Mutation
Location Mention ≠ Executable Location
Rewritten Query ≠ Whole-turn Business Truth
Fact Question ≠ State Update
```

---

# 三、最终增加了什么：TurnCommandSet

新增 request-scoped：

```text
TurnCommandSet
TurnCommand
TurnUnderstandingService
```

它是 ephemeral runtime semantics，不写入 Working Memory。

第一阶段 Command 只覆盖当前有真实需求的动作：

```text
SET_CONSTRAINT
CLEAR_CONSTRAINT
EXCLUDE_CONSTRAINT
SET_LOCATION_INTENT
ASK_DECISION_CONTEXT
ASK_SHOP_FACT
REFERENCE
```

不要把它讲成“通用 Agent DSL”。

当前定位更准确：

> 一个很小的 Turn Semantic Boundary，用来统一“这一轮用户允许系统做什么”。

---

# 四、为什么 Original Message 必须保留？Context Rewrite 为什么降级？

旧设计近似：

```text
Original Message
→ Rewrite
→ Effective Message
→ 后续所有模块使用 Effective Message
```

这在单一省略补全场景还可以，但 compound turn 会丢语义。

例如：

```text
我附近呢？除了东北菜应该都OK
```

如果看到“我附近”就提前改写成：

```text
在当前设备附近搜索餐饮商户
```

整个 Turn 后半段已经不可恢复。

因此新原则：

```text
Original Message
= Turn Semantics source

Context Rewrite
= reference / ellipsis enrichment

Retriever Query Rewrite
= consumer-specific derived representation
```

Rewrite 不再是业务事实总线。

面试一句话：

> Query Rewrite 是给某个下游消费者使用的派生表示，不应该替代原始 Turn 的业务语义。

---

# 五、最关键的边界：Mention ≠ Mutation

## 旧逻辑为什么错？

以前 BUSINESS_FOLLOW_UP 中会先运行 ConstraintExtractor：

```text
“这个日料咋样”
→ extractor: cuisine=日料
→ hasMutation(criteriaDelta)=true
→ APPLY_DELTA
```

这里最大的错误不是 cuisine 来源没记录，而是：

```text
抽到了字段
≠
用户允许修改状态
```

Provenance 只能告诉你“错误值从哪里来”，不能证明这笔写入本来应该存在。

---

## 新逻辑

最终职责拆成三层：

```text
TurnUnderstanding
→ 决定“能不能改”

ConstraintExtractor
→ 在允许后决定“具体改什么”

CriteriaMerger / ConversationStateService
→ 决定“怎么合并、怎么落状态”
```

这是当前最重要的面试表达。

---

# 六、为什么还经历了一次“双 Mutation Authority”返工？

第一版 Turn Semantics 上线后，`selectAction()` 已经改为：

```text
TurnUnderstandingService.shouldApplyReferenceMutation()
```

但 full regression 又发现 `route()` 中仍存在旧逻辑：

```text
isCompoundMutationFollowUp()
→ ensureCriteriaDelta()
→ hasMutation(criteriaDelta)
```

于是同一个 Turn 有两个裁判：

```text
Route 阶段：Extractor 判断 mutation
TurnPlan 阶段：Turn Semantics 判断 mutation
```

真实后果：

```text
第一家那个日本料理环境怎么样？
```

仍可能因为 `cuisine=日料` 被当成 compound mutation。

这说明：

> 新增一个 Authority 类不代表 Authority Boundary 已经成立；所有旧旁路都必须失去决定权。

最终 `5a03adf`：

```text
route()
→ hasStructuredReferenceMutation()
→ TurnUnderstandingService

selectAction()
→ 同一个 hasStructuredReferenceMutation()
→ YES 后才 ensureCriteriaDelta()
```

旧 `hasMutation(DecisionConstraints)` 完全删除。

如果 `TurnUnderstandingService` 缺失：

```text
Reference mutation → fail-closed
```

不会回退到“Delta 非空就允许写”。

---

# 七、Reference Fact Question 与 Compound Mutation 如何区分？

下面这些必须 read-only：

```text
这家有没有包厢？
这家有什么推荐菜？
这家人均多少？
第二家预算多少？
这个日料怎么样？
这家日料评价如何？
```

即使 Extractor 理论上能识别：

```text
预算
人均
日料
```

也不能获得 mutation permission。

真正允许 Reference Turn mutation 的正向证据主要是：

```text
1. ReferenceIntent.mutationAnchor
2. 明确 SET / CLEAR / EXCLUDE operator
3. 明确 relative mutation，例如“太贵”“便宜点”“太远”“近一点”
```

例如：

```text
第一家太贵了，第二家有插座吗？
```

可以同时产生：

```text
第一家 → mutationAnchor
第二家 → fact target
```

最终 TurnPlan：

```text
CriteriaIntent = APPLY_DELTA
ExecutionAction = BUSINESS_FOLLOW_UP
```

这就是 TurnPlan 存在的价值：

```text
State Mutation
和
Execution Action
```

是两个维度。

---

# 八、为什么不能简单把“预算”视为 Mutation Signal？

因为：

```text
第二家预算多少？
```

和：

```text
预算改成80
```

都出现“预算”，但一个是 query，一个是 command。

所以不能：

```text
contains("预算") → Mutation
```

同理：

```text
“日料”出现
≠ cuisine mutation
```

更一般的工程结论：

> Entity / Slot Mention 只是语义材料；Dialogue Act / Command 才决定是否产生状态 Delta。

---

# 九、negative cuisine 为什么需要新增 durable schema？

真实输入：

```text
除了东北菜应该都OK
```

旧模型只有：

```text
cuisine = 一个正向菜系
```

无法表达：

```text
ANY cuisine EXCEPT 东北菜
```

所以增加：

```text
excludedCuisines[]
```

这是 durable user constraint，不是 soft preference。

Merger 语义：

```text
除了东北菜都可以
→ clear cuisine
→ excludedCuisines += 东北菜

那就东北菜吧
→ cuisine=东北菜
→ excludedCuisines -= 东北菜
```

它还被加入 Search Domain，因此增删时会：

```text
invalidate current candidate projection
clear focusedShop
preserve historical RecommendationBatch
```

Current Projection 可以失效，但 Historical Fact 不删除。

---

# 十、negative cuisine 为什么还要经过 CuisineCanonicalizer？

最初 fallback Regex 只匹配：

```text
xxx菜
xxx料理
```

能识别“东北菜”，却识别不了：

```text
日料
火锅
烧烤
```

最后改为：

```text
抽取“除了 <candidate>”
→ CuisineCanonicalizer.canonicalize(candidate)
→ knownCanonicalValues 校验
```

例如：

```text
韩国料理 → 韩餐
日本料理 → 日料
```

未知文本不进入 excludedCuisines。

这体现一个复用原则：

> Turn Understanding 可以发现候选，但 canonical identity 仍由已有 Domain Authority 负责。

---

# 十一、POI 为什么必须再做 Grounding？

真实问题：

```text
targetArea=师大
```

只代表：

```text
用户提到“师大”
```

不代表：

```text
系统已经知道它在哪
```

因此：

```text
Location Mention
→ POI Resolution
→ Resolved SearchLocation
→ Execution
```

未解析 POI：

```text
LOCATION_RESOLUTION / clarification
```

禁止：

```text
targetArea=师大
+
旧 Browser GPS
→ silent execution
```

这里和 Administrative Authority 的思想一致：

```text
Mention ≠ Verified Identity
Verified Identity ≠ Executable Projection（除非满足 execution contract）
```

---

# 十二、Decision Context Query 为什么也属于 Turn Semantics？

用户会问：

```text
你的过滤条件是什么？
我什么时候说过日料？
为什么推荐第一家？
```

这些不是 Mutation，而是：

```text
ASK_DECISION_CONTEXT
```

当前内部类型仍复用：

```text
CURRENT_CRITERIA
CONSTRAINT_PROVENANCE
WHY_RECOMMENDED
EXECUTED_SEARCH_SCOPE
```

Turn Semantics 只负责识别 query intent；真正答案继续由 `DecisionContextQueryService` 从 Canonical State / Historical Decision Fact 读取。

即：

```text
Turn Understanding
≠ Answer Authority
```

---

# 十三、业内为什么也强调 Command / Flow，而不是“每层都重新猜意图”？

## Rasa CALM

Rasa 当前 CALM 把 Conversation Understanding 和确定性 Flow Policy 分开：语言层可以产生结构化 command，Flow Policy 作为 state machine 执行业务逻辑；Conversation Repair 还被单独设计成 correction、clarification、interruption、cancel 等 pattern。

这与本项目现在的演进很接近：

```text
TurnCommandSet
→ 表达用户本轮控制语义

TurnPlan / State Reducer
→ 确定性执行与状态更新
```

但不能说“我们实现了 CALM”。本项目只做了餐饮业务所需的很小子集，没有完整 flow stack、通用 correction/cancel/interruption engine。

参考：

- https://rasa.com/docs/reference/config/policies/flow-policy/
- https://rasa.com/docs/reference/primitives/patterns/
- https://rasa.com/docs/learn/concepts/conversation-patterns/

## Dialogflow CX

Dialogflow CX 的 state handler 强调 route/event handler 只有在对应 flow/page scope 内才生效，同时 fulfillment 与 transition target 分开。

面试可以借它解释：

> 同一句“附近有啥”，在正常聊天和 WAITING_RELAXATION 中语义不一定相同；状态本身应该参与当前可用 transition 的解释范围，而不是全局 intent classifier 永远只看文本。

参考：

- https://docs.cloud.google.com/dialogflow/cx/docs/concept/handler
- https://docs.cloud.google.com/dialogflow/cx/docs/concept/agent-design

---

# 十四、牛客近期面经为什么也会追这个？

近期 Agent 面经越来越常问：

```text
用户多轮改口怎么维护当前真实意图？
State / Context / Memory 怎么区分？
Intent Router 怎么做低置信度兜底？
为什么不是每轮都让模型重猜？
如何防止错误状态进入 Memory？
```

其中一份近期快手 Agent 研发面经的核心思路就是：不要简单拼历史，而是维护显式对话状态，每轮先抽取新增/修改/否定/确认等 Delta，再由状态归并器生成当前快照。

这和本项目的：

```text
Turn Semantics
→ Criteria Delta
→ Reducer
```

高度对应。

参考：

- https://www.nowcoder.com/discuss/907350302821576704
- https://www.nowcoder.com/discuss/916877706187276288
- https://www.nowcoder.com/discuss/908634420217675776

不要背帖子的标准答案。面试时优先讲自己的真实 Bad Case 和架构演进。

---

# 十五、最新 Evaluation 怎么讲？

`709c615` 阶段全量：

## Robustness Run139

```text
Cases: 48
Complete: 20/48
Route: 44/48
Tool: 47/48
Final Status: 37/48
Locality: 47/48
Context Rewrite: 7/7
```

相较 Run137：

```text
Complete +2
Route +1
Tool +1
Final Status 不变
Locality -1
```

## Conversation-v1 Run140

```text
Cases: 40
Complete: 28/40
Route: 37/40
Tool: 32/40
Final Status: 40/40
Locality: 40/40
```

相较 Run138：

```text
Complete -1
Route -1
Tool -1
Final Status 不变
Locality 不变
```

这两轮没有出现结构性大面积回归，但恰恰暴露了 BUSINESS_FOLLOW_UP 仍存在第二 Mutation Authority，随后才有 `5a03adf`。

因此必须准确说：

> Run139/140 是发现最后 authority leak 的阶段评测，不是 `5a03adf` 的 full regression 结果。

`5a03adf` 目前只完成：

```text
TurnUnderstandingServiceTest 11/11
TurnPlanTest 5/5
ChatOrchestrationServiceTest 44 tests / 0 failures / 1 skipped
```

Holdout 未运行。

---

# 十六、最值得面试讲的完整因果链

```text
没有 Canonical State
→ Chat History / 临时字段互相覆盖
→ Working Memory

有 Canonical State
→ 但同一 Turn 被多个模块重复解释
→ Canonical Turn Semantics

第一版 Turn Semantics
→ selectAction 已使用新 authority
→ route 仍保留旧 Extractor-based hasMutation
→ Full Regression 暴露双 Mutation Authority

最终
→ TurnUnderstanding 决定“能不能改”
→ Extractor 决定“具体改什么”
→ Merger / Reducer 决定“怎么落状态”
```

这比“修了日料 Bug”强得多。

---

# 十七、当前仍然不能吹的边界

## 1. Turn Semantics Phase 1 仍是轻量规则 / 结构化语义层

还不是一个完整 Dialogue Act 模型，也没有通用 command planner。

## 2. Compound Intent 仍未完全解决

虽然 TurnPlan 可以同时表达：

```text
CriteriaIntent
+
ExecutionAction
```

但复杂 compound turn 的 turn-level memory contract 仍有历史失败。

## 3. Provenance 目前只有来源类型

当前：

```text
USER_EXPLICIT
DERIVED
SYSTEM_DEFAULT
```

还没有 sourceTurn/sourceEvent，因此不能稳定回答“你具体哪一轮说的”。

## 4. WAITING_RELAXATION 的自然语言恢复仍是旧契约

当前暂停态主要依赖固定 relaxation options 和部分位置恢复规则；还没有统一到新的 Turn Command/Repair Pattern 体系。

这已经成为下一阶段真实聊天暴露的新边界，不能说 Phase 1 已经解决所有状态恢复语义。

## 5. `5a03adf` 尚未重新跑 full regression

所以当前只能说 Mutation Authority 已在代码和定向测试上完成收口，不能声称所有 Dataset 已验证。

---

# 十八、面试高频追问速答

## Q1：Working Memory 和 Turn Semantics 有什么区别？

```text
Working Memory
= 当前业务真值

Turn Semantics
= 本轮用户希望系统做什么
```

前者是 durable state，后者是 request-scoped command。

---

## Q2：为什么不直接让 ConstraintExtractor 同时决定 mutation？

因为抽取值和授权写状态是两回事。

```text
“第二家预算多少”
```

可以抽到“预算”这个概念，但这是 query，不是 SET budget。

Extractor 如果同时拥有 write permission，就会把 Mention / Question 误写成 State Mutation。

---

## Q3：为什么 fail-closed？

错误地“不改状态”最多少应用一项用户修改，可以继续澄清；错误地“改了状态”会污染后续所有轮次，而且用户通常很难察觉根因。

因此 Reference Follow-up 对 mutation 不确定时：

```text
宁可不写
也不根据非空 Delta 猜写
```

---

## Q4：TurnCommandSet 是不是替代 Routing？

不是。

Phase 1 只接管几个最危险的语义边界：

```text
mutation permission
context query
reference control
location intent
negative cuisine
```

旧 Routing 仍负责其他顶层 Action。

这是增量迁移，不是大爆炸重写。

---

## Q5：为什么不一次重构所有 Route？

因为当前问题有明确证据集中在 authority overlap。如果一次把整条 Pipeline 重写，回归面会远大于收益，也难判断指标变化来自哪里。

策略是：

```text
先建立新 authority
→ 用真实 Bad Case 验证
→ Full Regression 找旧旁路
→ 删除旁路
→ 再逐步迁移
```

---

## Q6：最大的工程收获是什么？

> 多轮 Agent 不仅需要 Canonical State，还需要 Canonical Turn Semantics。只解决“系统现在相信什么”不够，还要解决“这一轮谁有权修改这些事实”。模型可以提供语义候选，但写状态必须经过明确的 command permission；否则任何一次实体提取都可能变成隐式状态污染。

---

# 十九、建议背熟的新增 6 条不变量

在 Working Memory v2 的 12 条基础上，再加：

```text
13. Mention ≠ Mutation

14. Fact Question ≠ State Update

15. Original Turn ≠ Rewritten Query

16. Location Mention ≠ Executable Search Anchor

17. Extracted Delta ≠ Mutation Permission

18. One Semantic Question → One Authority
```

其中第 18 条是本阶段最重要的复盘：

> 同一个“能不能改状态”的问题，不能在 route、extractor、TurnPlan 三处分别回答。
