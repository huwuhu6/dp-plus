# AI 消费决策 Agent——Working Memory 主线补充：决策可解释性与约束溯源

> 当前实现口径以 main `8407c63488cba7bd97ba9c7c55b851e154426814` 为 Code Truth。

---

## Q1：为什么要专门做 `DECISION_CONTEXT_QUERY`？

真实对话里出现两类问题：

```text
为什么推荐这家？
你是根据什么推荐的？

我前面有说过安静吗？
这个预算是我说的吗？
现在按哪些要求帮我找？
```

它们既不是：

```text
START_DECISION
→ 修改约束 / 重新检索
```

也不是：

```text
BUSINESS_FOLLOW_UP
→ 查询商户营业时间、地址、优惠、评价等外部事实
```

更不是普通：

```text
GENERAL_CHAT
```

它们本质上都是：

> **对已经存在的决策上下文进行 grounded、只读查询。**

所以最终新增一个顶层 Route：

```text
DECISION_CONTEXT_QUERY
```

而不是继续增加：

```text
DECISION_EXPLANATION
MEMORY_AUDIT
WHY_RANKED
CONSTRAINT_QUERY
...
```

顶层 Route 按控制流和副作用边界划分，具体问题类型留在 Route 内部结构化表达。

---

## Q2：为什么没有复用 BUSINESS_FOLLOW_UP 或 GENERAL_CHAT？

### BUSINESS_FOLLOW_UP

职责是：

```text
围绕已经推荐的具体商户查询外部业务事实
```

例如：

```text
几点关门？
地址在哪？
有没有优惠？
评价怎么样？
```

但：

```text
为什么推荐它？
```

问的是系统内部决策事实，不是商户外部事实。

### GENERAL_CHAT

如果为了回答：

```text
我之前说过安静吗？
```

把完整 Working Memory、constraintSources、推荐证据直接扔给 GENERAL_CHAT，让 LLM 自己回忆和推断，会出现：

```text
Canonical State
vs
LLM 重新扫描 Chat History
```

双真相。

因此最终职责边界是：

```text
BUSINESS_FOLLOW_UP
→ 外部商户事实

DECISION_CONTEXT_QUERY
→ 内部决策事实

GENERAL_CHAT
→ 非决策内省的一般对话
```

---

## Q3：为什么只增加一个顶层 Route？

内部第一版只定义三个 QueryType：

```text
WHY_RECOMMENDED
CONSTRAINT_PROVENANCE
CURRENT_CRITERIA
```

例如：

```text
为什么推荐第二家？
→ WHY_RECOMMENDED

我说过安静吗？
→ CONSTRAINT_PROVENANCE

现在按哪些要求帮我找？
→ CURRENT_CRITERIA
```

真实用户还可能问：

```text
我没说要安静，你为什么还推荐这家？
```

这种一句话同时包含推荐解释和来源质疑。

如果顶层拆成：

```text
DECISION_EXPLANATION
MEMORY_AUDIT
```

反而会让 Router 在两个相近 Route 之间竞争。

所以：

> 顶层只区分“这是一次决策上下文只读查询”，内部再解析具体 QueryType。

---

## Q4：`DECISION_CONTEXT_QUERY` 为什么不会修改 Working Memory？

它的核心 Contract 是：

```text
CriteriaIntent = NONE
```

现有 Pipeline：

```text
Bootstrap
→ ContextRewrite
→ IntentRouting
→ CriteriaReduction
→ PolicyGuard
→ Execution
```

`CriteriaReductionNode` 本来就只在：

```text
CriteriaIntent.APPLY_DELTA
```

时执行 reducer。

所以新增 Action 后不需要新增 Pipeline Node，只在现有 Execution 分支执行只读 Query Handler 即可。

严格不变量：

```text
不 reduceCriteria
不切换 Task
不 invalidate candidatePool
不 append RecommendationBatch
不修改 focusedShop
不修改 criteria
不修改 constraintSources
不修改 searchLocation
不增加 Working Memory version
```

允许正常记录：

```text
Assistant Message
Runtime Trace / Event
```

---

## Q5：“为什么推荐这家”的 Source of Truth 是什么？

不能重新执行推荐，也不能拿当前 Criteria 重解释过去结果。

标准链路：

```text
ReferenceIntent
↓
ResolvedShopReference
↓
RecommendationBatch.decisionSessionId
↓
ConsumptionDecisionService.getDecision(sessionId)
↓
AiDecisionSession.resultJson
↓
当时持久化的 DecisionResponse
↓
Recommendation.matchedReasons / evidence
```

例如：

```text
第一轮预算 150
→ 推荐 ShopA

第二轮预算改成 80

用户：最开始第一家为什么推荐？
```

必须读取第一轮当时保存的 Recommendation，不能拿当前预算 80 重新解释 ShopA。

否则会产生：

```text
Temporal Leakage
```

即“现在的状态穿越回去解释过去的决策”。

---

## Q6：为什么 RecommendationBatch 不直接保存 matchedReasons / evidence？

当前 RecommendationBatch 的职责是轻量历史索引：

```text
decisionSessionId
candidate shop identity
price
distance
```

完整 DecisionResponse 已经持久化在：

```text
AiDecisionSession.resultJson
```

如果再把：

```text
matchedReasons
evidence
review 文本
```

复制进 RecommendationBatch，会导致同一推荐事实出现两份持久化真相。

同时 Working Memory 使用版本化整快照：

```text
WM v10
WM v11
WM v12
...
```

历史 evidence 越多，每次 append 都会重复复制长文本，导致快照膨胀。

所以最终分工：

```text
Working Memory
→ 当前业务状态

RecommendationBatch
→ 历史推荐 identity / decisionSession pointer

AiDecisionSession.resultJson
→ 当时完整 Decision Fact
```

这是比“所有东西都塞进 Memory”更合理的状态边界。

---

## Q7：为什么 `matchedReasons` 和 `evidence` 比 score 更适合给用户解释？

推荐结果内部还有：

```text
score
semanticScore
```

但不直接展示：

```text
score = 83.42
semanticScore = 0.871
```

原因不是“保密”，而是这些连续数值属于排序实现细节，本身不能构成业务解释。

用户真正需要的是：

```text
为什么符合我的要求？
有什么事实支撑？
```

所以用户可见解释主要读取：

```text
matchedReasons
+
evidence
+
必要的 avgPrice / distance 等客观属性
```

原则：

> **Explain business facts, not implementation scores.**

---

## Q8：“我之前有说过安静吗？”为什么不能只看 `criteria.preferences`？

假设当前：

```text
preferences = [安静]
```

它只能证明：

```text
安静当前生效
```

不能证明：

```text
用户明确说过“安静”
```

因为偏好可能来自：

```text
USER_EXPLICIT
SYSTEM_DEFAULT
DERIVED
```

例如：

```text
用户：想找安静一点的
→ 安静 = USER_EXPLICIT

用户：适合聊天的
→ 安静 = DERIVED
```

最终 canonical value 都是：

```text
安静
```

但 provenance 完全不同。

所以：

> **Value 不足以回答“是谁产生的这个值”。**

---

## Q9：Preference Provenance 为什么必须按 value 记录？

不能只写：

```text
preferences = USER_EXPLICIT
```

因为：

```text
preferences = [安静, 约会, 不排队]
```

三项可能分别来源于：

```text
安静       → USER_EXPLICIT
约会       → DERIVED
不排队     → USER_EXPLICIT
```

因此复用现有：

```text
Map<String, ConstraintSource> constraintSources
```

但 key 使用：

```text
preference:<canonicalPreference>
```

例如：

```text
preference:安静  → USER_EXPLICIT
preference:约会  → DERIVED
```

这里的重要原则是：

> List 型 Constraint 的 provenance 需要 element-level，而不是 field-level。

---

## Q10：为什么 provenance 不能在 StateService 中事后猜？

StateService 看到：

```text
新加入了 preference=安静
```

不知道它来自：

```text
用户明确说“安静”
```

还是：

```text
用户说“适合聊天”
→ 语义解释成安静
```

因此当前数据流：

```text
ConstraintExtractor
→ sourceHints

ConversationCriteriaMerger
→ sourceUpdates

ConversationStateService
→ 持久化 constraintSources
```

职责分别是：

```text
Extractor
= 知道这个值为什么产生

Merger
= 知道这个值是否真正进入 canonical state

StateService
= 持久化已经确定的 provenance
```

StateService 不重新做 NLU。

---

## Q11：当前有哪些 explicit / derived 例子？

### 安静

```text
想找安静一点的
→ preference:安静 = USER_EXPLICIT
```

```text
适合聊天的
→ canonical preference=安静
→ preference:安静 = DERIVED
```

### 约会

```text
想找适合约会的餐厅
→ preference:约会 = USER_EXPLICIT
```

```text
和女朋友 / 男朋友 / 情侣一起吃饭
→ canonical preference=约会
→ preference:约会 = DERIVED
```

这里区分的是：

```text
Canonical Meaning
vs
User Literal Expression
```

不能因为 canonical value 相同就把来源也合并掉。

---

## Q12：Legacy Working Memory 没有 preference provenance 怎么办？

旧 snapshot 可能存在：

```text
preferences=[安静]
```

但：

```text
constraintSources[preference:安静]
```

不存在。

这时不能反推：

```text
“你之前明确说过安静。”
```

正确回答：

```text
当前记录里存在“安静”这个条件，
但旧状态没有保存来源，
所以不能确认是你明确提出的还是系统推导的。
```

这是向后兼容中的重要原则：

> Missing provenance should produce uncertainty, not fabricated certainty.

---

## Q13：删除 Preference 时为什么还要删除 provenance？

例如：

```text
用户：不要安静了
```

如果只：

```text
preferences.remove("安静")
```

但保留：

```text
constraintSources["preference:安静"] = USER_EXPLICIT
```

后续 State 中就会出现：

```text
Value 已不存在
Provenance 仍存在
```

这是 Ghost Provenance。

所以 remove 同时产生：

```text
sourceUpdates["preference:安静"] = null
```

最终 StateService 删除该 source。

---

## Q14：为什么“附近”和 radiusKm 也需要分别溯源？

例如：

```text
用户：我附近找火锅
```

可以得到：

```text
nearby=true
source=USER_EXPLICIT
```

但默认半径：

```text
radiusKm=3.0
source=SYSTEM_DEFAULT
```

所以用户问：

```text
我之前有说过附近搜索吗？
```

回答：

```text
是，附近语义是你明确提出的。
```

但如果问：

```text
3 公里是我说的吗？
```

应该回答：

```text
不是，3km 是系统对“附近”的默认解释。
```

这也是 Value 与 Provenance 拆开的价值。

---

## Q15：Reference 为什么必须复用原来的 ReferenceIntent？

用户可能问：

```text
为什么推荐第一家？
为什么第二家排这么前？
为什么推荐这个？
为什么推荐刚才那个？
最开始第一家为什么推荐？
```

项目已经有：

```text
ReferenceIntentExtractor
→ BatchAwareReferenceResolver
→ ResolvedShopReference
```

所以 Decision Context Query 只消费：

```text
ResolvedShopReference
```

不重新写：

```text
contains("第一家") → list[0]
```

最终仍然保持：

> Reference extraction / resolution 在系统里只有一套 authority。

Routing 只负责判断：

```text
当前 Turn 是否需要先做 Reference Resolution
```

而不是自己决定最终 shopId。

---

## Q16：为什么 Reference 必须在 Rewrite 之前预判 contextRequired？

Pipeline 顺序是：

```text
Bootstrap
→ ContextRewrite
→ IntentRouting
```

如果直到 IntentRouting 才发现：

```text
“为什么推荐刚才那个？”
```

需要商户引用，那么 ContextRewrite 已经错过了。

因此 Bootstrap 阶段的 Routing Assessment 必须先判断：

```text
DECISION_CONTEXT_QUERY
+
Reference expression
→ contextRequired=true
```

ContextRewrite 才会提前完成：

```text
ReferenceIntent
→ ResolvedShopReference
```

随后正式 Route 只消费已经冻结的引用结果。

这仍然符合：

```text
Rewrite 负责补上下文
Routing 负责业务 Action
```

两者没有职责倒置。

---

## Q17：为什么没有新增 ContextInspectionNode？

因为现有 Pipeline 已经能表达这个控制流。

```text
DECISION_CONTEXT_QUERY
→ CriteriaIntent.NONE
```

自然跳过 Reducer。

ExecutionNode 再根据 Action 执行：

```text
DecisionContextQueryService
```

如果再加：

```text
ContextInspectionNode
DecisionExplanationNode
MemoryAuditNode
```

只是为了一个新 Action 改 Pipeline topology，没有新的架构价值。

所以选择：

> 新行为优先进入现有 Action Contract，而不是每个行为新建 Pipeline Node。

---

## Q18：当前 `DecisionContextQueryService` 为什么先做确定性 Facts，再生成回答？

核心思想是：

```text
Fact Selection
≠
Natural Language Rendering
```

WHY_RECOMMENDED 先确定：

```text
哪个 session
哪个 shop
哪些 matchedReasons
哪些 evidence
```

CONSTRAINT_PROVENANCE 先确定：

```text
哪个 key
当前 value
ConstraintSource
```

只有这些事实确定后，才生成自然语言答案。

这样即使未来加入 LLM 润色，也必须满足：

```text
LLM only renders grounded facts
```

而不是让模型重新决定事实。

---

## Q19：这一条线最核心的状态模型是什么？

最终可以总结为三个维度：

```text
Value
+
Provenance
+
Historical Decision Fact
```

### Value

```text
当前生效条件是什么
```

来源：

```text
DecisionTaskState.criteria
```

### Provenance

```text
这个条件是谁产生的
```

来源：

```text
DecisionTaskState.constraintSources
```

### Historical Decision Fact

```text
当时为什么推荐这家
```

来源：

```text
AiDecisionSession.resultJson
```

通过：

```text
RecommendationBatch.decisionSessionId
```

建立历史索引。

然后：

```text
DECISION_CONTEXT_QUERY
```

只是对这些事实做只读 Projection。

---

## Q20：这条支线里最值得讲的工程原则有哪些？

### 1. State Value ≠ State Provenance

```text
安静当前生效
```

不等于：

```text
用户明确说过安静
```

### 2. Current State ≠ Historical Decision Fact

不能拿今天的 criteria 解释昨天的 recommendation。

### 3. Pointer ≠ Payload Duplication

RecommendationBatch 保存 session pointer，不复制完整历史证据。

### 4. Read Query ≠ Mutation

用户问“为什么”时不能隐式改变 focus、criteria、candidatePool 或 WM version。

### 5. Missing Provenance ≠ USER_EXPLICIT

旧状态没有来源，就回答不确定，而不是猜。

### 6. Canonicalization ≠ User Literal Expression

```text
女朋友 → 约会
聊天 → 安静
```

canonical preference 可以一样，但来源仍应标 DERIVED。

### 7. One Reference Authority

`ReferenceIntentExtractor + Resolver` 是唯一指代解析机制，新的 Query 不重新造解析规则。

---

## Q21：面试怎么用 1～2 分钟讲？

可以这样讲：

> Working Memory 做到后面我发现，只记“当前有什么条件”还不够。真实用户会追问“我之前说过安静吗”“这个预算是我定的吗”“为什么当时推荐第一家”。这类问题不是新的推荐请求，也不是商户事实查询，而是对决策状态的只读内省。所以我没有增加很多 Explain/Audit Route，而是增加一个 `DECISION_CONTEXT_QUERY`，内部只区分推荐解释、约束来源和当前条件。
>
> 这里我把可解释状态进一步拆成 Value、Provenance 和 Historical Decision Fact。当前值来自 Task.criteria，来源来自 constraintSources；List 型 preference 因为每个 element 可能来源不同，所以按 `preference:安静` 这种 value-level key 记录。比如用户直接说“安静”是 USER_EXPLICIT，“适合聊天”归一成安静则是 DERIVED。
>
> 历史推荐理由我没有复制进 Working Memory，因为 WM 是版本化整快照，评论 evidence 会导致状态越来越重。RecommendationBatch 只保存 decisionSessionId 和候选 identity，需要解释时通过 sessionId 回查当时 `AiDecisionSession.resultJson` 里的完整 DecisionResponse。这样也避免了用户后来改预算后，系统拿当前条件重新解释旧推荐的 temporal leakage。整个 Query 严格只读，不 reduce、不换 Task、不改 focus、不增加 WM version。

---

## Q22：当前验证怎么准确表达？

实现阶段进行了 targeted regression。

第一轮 `DECISION_CONTEXT_QUERY`：

```text
103 tests
0 failures
0 errors
1 skipped
```

覆盖：

```text
Route
QueryType
Historical Recommendation lookup
USER_EXPLICIT / DERIVED / Legacy provenance
Preference source removal
Read-only invariant
ReferenceIntent
```

冻结前边界修复：

```text
77 tests
0 failures
0 errors
1 skipped
```

额外覆盖：

```text
女朋友 / 男朋友 / 情侣 → 约会 = DERIVED
显式约会 = USER_EXPLICIT
nearby / radius key precedence
“为什么推荐刚才那个”在 Rewrite 前标记 contextRequired
focused historical reference
```

Targeted E2E 还验证：

```text
和女朋友吃饭
→ 查询约会来源 = DERIVED

我附近找火锅
→ nearby=USER_EXPLICIT
→ radiusKm=3.0 / SYSTEM_DEFAULT

推荐后问“为什么推荐刚才那个”
→ DECISION_CONTEXT_QUERY
→ historical recommendation grounding
```

注意：

> 最后几轮没有重新执行完整 robustness / conversation-v1 / holdout，因此不能声称这些历史全量指标代表当前 HEAD。

---

## 最终图

```text
                     DECISION_CONTEXT_QUERY
                              │
          ┌───────────────────┼────────────────────┐
          │                   │                    │
 WHY_RECOMMENDED      CONSTRAINT_PROVENANCE   CURRENT_CRITERIA
          │                   │                    │
 ResolvedShopRef       activeTask.criteria     activeTask.criteria
          │                   +
 RecommendationBatch  constraintSources
          │
 decisionSessionId
          │
 AiDecisionSession.resultJson
          │
 historical Recommendation
 matchedReasons / evidence
```

最核心的一句话：

> **Memory 的价值不只是“记住了什么”，而是系统能够确定一个事实现在是什么、从哪来的，以及当时为什么影响了决策。**
