# AI 消费决策 Agent——评测系统面试资料

> 这份资料专门准备“你们的评测系统怎么做”的面试追问。重点不是背几个指标，而是能解释：**为什么要这样分层、每层怎么构造 Ground Truth、怎么执行、怎么定位 Bad Case、哪些指标当前项目真的实现了、哪些只是行业常见但项目还没有。**
>
> 代码事实以当前 `main` 的 `AiEvaluationService`、`AiConversationEvaluationService`、`ConversationEvaluationDatasetLoader` 等实现为准。面试时不要把“行业常见指标”说成“项目已经实现”。

---

## Q1：你们的评测系统整体是怎么设计的？

项目不是只有一套“最终回答打分”，而是拆成两层：

```text
第一层：Decision / Retrieval Evaluation
AiEvaluationService

主要测：
约束抽取 → 检索/排序 → 推荐结果 → 事实约束 → 性能成本

第二层：Conversation / Agent Trajectory Evaluation
AiConversationEvaluationService

主要测：
多轮理解 → Route → Context Rewrite → Working Memory
→ Tool → Reference → Recovery → 最终状态
```

原因是这个系统不是单纯的聊天机器人，而是一条有状态的 Agent 链路。一次最终回答错误，可能来自完全不同的层：

```text
Query 理解错
→ Route 错
→ Working Memory 写错
→ Location/Reference 解析错
→ Tool 选错或参数错
→ 检索没召回
→ Rerank 排错
→ 最终生成错
```

如果只测“最终回答好不好”，发生回归时基本无法定位责任层。

所以项目的评测核心思想是：

> **Outcome Evaluation + Trajectory Evaluation + Component Metrics。**

其中能够确定验证的业务 Contract 尽量使用 deterministic grader，不让另一个 LLM 再猜一次。

### 面试回答

我把评测拆成两套。第一套偏单轮 Decision/Retrieval，主要看约束抽取、Recall@K、MRR、硬约束、Evidence Coverage、模型成功率、Token 和延迟；第二套是多轮 Conversation Evaluation，让脚本化用户请求走真实 `ChatOrchestrationService` 主链路，每轮采集 Route、Working Memory、Recommendation、Tool Call 等快照，再做按轮和跨轮断言。这样最终 Case 失败后可以继续判断到底是状态、工具、检索还是生成出了问题，而不是只得到一个总分。

---

## Q2：为什么一定要做“分层评测”，不能只测最终答案？

这是 RAG 和 Agent 面试里很常见的追问。

假设用户问：

```text
“附近 100 元以内适合约会的日料”
```

最终答案没推荐对，至少有下面几种可能：

```text
1. ConstraintExtractor 没提取出预算 100
2. Route 没进入推荐链路
3. Location 用错了位置
4. MySQL hard filter 把正确店过滤掉了
5. Milvus 没召回正确店
6. Rerank 把正确店排到了后面
7. LLM 最终叙事遗漏/改写了已有事实
```

一个“最终答案 = 0 分”不能告诉我该改哪一层。

所以实际排查是：

```text
Failure
↓
Route 是否正确？
↓
Working Memory 是否正确？
↓
Tool / Location / Reference 是否正确？
↓
Retriever 是否命中？
↓
Rerank 是否把相关结果提前？
↓
Generator 是否忠实使用事实？
```

这也是为什么项目后期增加了 diagnostics，而不是看到 Case 红了就继续调 Prompt。

---

## Q3：评测 Case 是怎么存的？为什么后来改成 JSONL？

Conversation Evaluation 当前优先读取：

```text
src/main/resources/eval/datasets/{datasetVersion}.jsonl
```

Loader 的逻辑是：

```java
public List<AiConversationEvaluationCase> loadCases(String datasetVersion) {
    List<AiConversationEvaluationCase> fromFile = loadFromClasspath(datasetVersion);
    if (fromFile != null) {
        return fromFile;
    }
    return caseMapper.selectList(
        new QueryWrapper<AiConversationEvaluationCase>()
            .eq("active", true)
            .eq("dataset_version", datasetVersion)
            .orderByAsc("id")
    );
}
```

也就是：

```text
Git 版本化 JSONL
        ↓ 文件不存在
旧 MySQL Case fallback
```

JSONL 的好处不是“JSON 比数据库高级”，而是评测集本身也是代码资产：

```text
Case 变更可以 code review
Dataset 和 Git commit 能一起追踪
不同分支能复现同一套 Ground Truth
避免本地数据库数据漂移导致结果不可比
```

JSONL DTO 中支持：

```text
turns
expectedRoutes
expectedContextRewrites
expectedToolNames
expectedToolArguments
expectedFinalStatus
expectedShopIds
expectedCity
expectedErrorCount
expectedRecoveryRoutes
expectedMemory
expectedUnseenFromTurn
expectedUnseenPairs
expectedTurnStates
expectedToolsByTurn
expectedRelations
```

所以它已经不是简单的“输入 + 一个标准答案”，而是一条 trajectory specification。

---

## Q4：你们怎么跑一次 Conversation Evaluation？

入口在：

```text
POST /ai/evaluations/conversation-runs
POST /ai/evaluations/conversation-runs/holdout
POST /ai/evaluations/conversation-runs/robustness
```

Robustness 还支持指定 `caseCodes` 做小范围回归。

执行流程大致是：

```text
1. 根据 datasetVersion 加载 active Case
2. 创建 EvaluationRun
3. 记录 model / datasetVersion / gitCommit / caseCount
4. 异步执行 Case
5. 每个 Case 创建独立 eval-* chatId
6. 按 turns 顺序调用真实 ChatOrchestrationService.chat()
7. 每轮采集 response + WM snapshot + Tool Call + model trace
8. 执行 deterministic assertions
9. 持久化 CaseResult
10. 聚合 Run 指标
```

核心不是自己模拟一份 Agent，而是直接复用生产主入口：

```java
response = chatOrchestrationService.chat(
    request,
    null,
    event -> collectStageTrace(event, stageStartedAt, stageTrace)
);
```

这样评测和用户真实请求走的是同一套编排逻辑，避免“测试代码通过，但生产路径根本没走这段代码”。

Conversation Run 是异步的，提交后可以轮询：

```text
GET /ai/evaluations/conversation-runs/{runId}
```

失败诊断：

```text
GET /ai/evaluations/conversation-runs/{runId}/diagnostics
```

与 Baseline 比较：

```text
GET /ai/evaluations/conversation-runs/{runId}/compare/{baselineRunId}
```

---

## Q5：评测集怎么划分？为什么要有 Holdout 和 Robustness？

项目里至少存在这些语义不同的数据集：

```text
seed-v2 / holdout-v1
→ Decision / Retrieval Evaluation

conversation-v1
→ Conversation 主流程回归

conversation-robustness-v1
→ 多轮状态、引用、恢复、边界条件等高风险 Case

conversation-holdout-v1
→ 独立 Holdout，防止为了已知 Case 反复特调
```

三类 Case 的职责不同。

### Main regression

覆盖真实主流程，保证日常开发没有把常见行为改坏。

### Robustness

故意打系统脆弱边界，例如：

```text
A → B → A 的历史任务切换
修改条件后 candidatePool 是否失效
旧 ordinal 是否错误绑定到新候选
“第二家”跨轮引用
换一批时是否重复已经展示过的店
一次 Turn 同时存在 Reference + Mutation
错误后能否恢复
位置变化后旧地区候选是否泄漏
```

### Holdout

最大的作用不是“多一批 Case”，而是防止 Dataset Overfitting。

如果某个 Bug 出现后，我们不断修改：

```text
代码
Prompt
Ground Truth
```

直到 main regression 100%，这个 100% 可能只是把系统训练成了“会做这批题”。Holdout 应尽量少参与日常针对性修复，用于阶段性验证修改是否具有泛化性。

---

## Q6：Ground Truth 怎么设计？LLM 输出有随机性，怎么保证评测稳定？

这里有一个项目里真实踩过的坑：早期部分 Case 把 Ground Truth 绑定在 LLM 偶然产生的中间字段上。

例如一个 Case 的真正业务要求是：

```text
用户切换需求后，旧条件不能继续污染新任务
```

如果 Ground Truth 却写成：

```text
LLM 本轮 cuisine 必须恰好抽成某个值
```

那同一个业务行为可能正确，但模型换一种等价表示，Case 仍然失败。

后来原则改成：

> **Golden Dataset 应尽量锁定稳定的业务 Contract，而不是锁定概率模型的偶然中间产物。**

例如测试“第二家怎么样”，不应该把当时某个固定商户 ID 永久写死，因为检索数据变化后第二名可能改变。

更稳的断言是：

```text
当前 Tool 的 shopId
=
指定上一轮 recommendation snapshot 的第 2 个 shopId
```

这测试的是“ordinal binding”这个稳定 Contract，而不是某个偶然排序结果。

---

# RAG / Retrieval Evaluation

## Q7：你们怎么评测 RAG？

先纠正一个口径：这个项目不是传统“PDF Chunk 问答型 RAG”，而是：

```text
MySQL structured hard filtering
+
Milvus semantic retrieval（商户 Profile / Review）
+
确定性 rerank
+
基于结构化事实和证据生成回答
```

所以面试里可以说它具备 RAG 的 Retrieval + Grounded Generation 评测问题，但不要说成“知识库文档 Chunk RAG”。

评测应该拆三层：

```text
Retriever
├─ Recall@K
├─ MRR
├─ Precision@K / Hit@K / nDCG（行业常见，当前主评测未全部实现）
└─ Evidence Coverage

Generator / Grounding
├─ Factual Consistency
├─ Faithfulness / Groundedness（行业常见）
├─ Answer Relevance（行业常见）
└─ Citation Accuracy（如果系统提供引用）

System
├─ Hard Constraint Violation
├─ Task / Status Correctness
├─ No-result / Recovery
├─ Latency
└─ Token / Cost
```

当前项目真正实现得比较明确的是：

```text
Recall@K
MRR
Evidence Coverage
部分可验证 Hard Constraint Violation
Factual Consistency 字段
模型成功/失败
Token
Latency
```

其中 `Precision@K / nDCG / LLM Judge Faithfulness` 不能说成已经实现。

---

## Q8：Recall@K 在项目里怎么算？

每个有 `expectedShopIds` 的 Case 都有一组相关商户：

```text
expected = Ground Truth relevant shops
actual   = 当前最终返回 Top-N shops
```

代码逻辑：

```java
private double recall(List<Long> expectedIds, List<Long> actualIds) {
    Set<Long> actual = new HashSet<>(actualIds);
    int matched = 0;
    for (Long expected : expectedIds) {
        if (actual.contains(expected)) matched++;
    }
    return (double) matched / expectedIds.size();
}
```

数学上：

```text
Recall@K = |Relevant ∩ TopK| / |Relevant|
```

例如 Ground Truth 有 4 家相关店，Top5 召回其中 3 家：

```text
Recall@5 = 3 / 4 = 0.75
```

当前 evaluator 中 `actualIds` 是最终返回候选列表，因此实际 K 由当次返回 Top-N / `maxCandidates` 决定。

### 为什么 Recall 很重要？

因为 Retriever 没把正确候选召回来，后面的 Rerank 和 LLM 再强也救不回来。

---

## Q9：MRR 到底怎么算？和 Recall 有什么区别？

项目里的单个 Case 先计算 Reciprocal Rank：

```java
private double reciprocalRank(List<Long> expectedIds,
                              List<Long> actualIds) {
    Set<Long> expected = new HashSet<>(expectedIds);
    for (int index = 0; index < actualIds.size(); index++) {
        if (expected.contains(actualIds.get(index))) {
            return 1D / (index + 1);
        }
    }
    return 0D;
}
```

也就是说，一个 Query 即使有多个正确结果，RR 只看：

> **第一个相关结果排在第几位。**

例如：

```text
结果：[错, 错, 对, 对]
第一个 relevant 在第 3 位
RR = 1/3
```

然后对所有参与 ranking evaluation 的 Query 求平均：

```text
MRR = (RR1 + RR2 + ... + RRn) / n
```

这也是为什么不能把 MRR 解释成“一个 Query 内所有正确文档倒数排名之和”。那不是这里的 MRR 定义。

### Recall 和 MRR 分别回答什么？

```text
Recall@K：相关结果有没有尽量被找全？
MRR：最早的一个相关结果能不能尽量靠前？
```

因此：

```text
Recall 高 + MRR 低
→ 相关结果找到了，但排得靠后

Recall 低
→ Retriever 本身存在漏召回
```

### 为什么还可能需要 nDCG？

如果未来 Ground Truth 不只是 relevant / irrelevant，而是有多级相关性：

```text
非常匹配 = 3
比较匹配 = 2
一般 = 1
不相关 = 0
```

这时 nDCG 会比 MRR 更合适，因为它会考虑整个 TopK 的位置和 graded relevance，而 MRR 只关心第一个 relevant。

当前项目没有把 nDCG 作为主评测指标，因此面试中只能说“如果要进一步评估整个排序质量，我会补 nDCG”。

---

## Q10：怎么评测 Rerank 是否真的有效？

正确做法不是只看最终推荐感觉“更合理”，而是控制变量：

```text
固定 Query
固定 Ground Truth
固定 Retriever candidate set
只替换 Rerank 策略
```

比较：

```text
MRR
nDCG（如果有多级相关标注）
TopK relevant position
Hard Constraint Violation
端到端任务结果
Latency
```

如果 Retriever 已经 Recall 很高，但 MRR 低，通常优先检查 Rerank。

如果 Recall 本身低，调 Rerank 没意义，因为正确结果压根没有进入候选集。

项目目前有 `retrievalStrategyVersion`，Decision Evaluation Run 会记录它，可以用于策略版本 A/B。

---

## Q11：Evidence Coverage 是什么？它等于 Faithfulness 吗？

不是。

当前项目的 Evidence Coverage 定义非常具体：

```java
int evidenceCovered = 0;
for (DecisionRecommendation item : result) {
    if (!item.getEvidence().isEmpty()) evidenceCovered++;
}
metrics.setEvidenceCoverageRate(
    result.isEmpty() ? 0D
        : round((double) evidenceCovered / result.size())
);
```

也就是：

```text
Evidence Coverage
=
最终推荐中具有非空 evidence 的候选数
/
最终推荐候选总数
```

它回答的是：

> 推荐出来的商户，有多少具备证据支撑。

但它不能证明：

```text
最终自然语言里的每一句话都被 evidence 支撑
```

所以它不能直接等价于 Faithfulness / Groundedness。

如果面试官继续追，应该主动说：

> 当前 Evidence Coverage 是 candidate-level coverage，不是 sentence-level entailment。要做严格 Faithfulness，我会把最终回答拆成 claim，再检查每个 claim 是否能被结构化事实或 evidence 支持，可以人工标注，也可以用经过人工校准的 LLM Judge 辅助。

---

## Q12：你们怎么测“幻觉”和事实一致性？

项目有 `factualConsistent`、`narrativeRejected` 等事实安全相关字段，生成侧也存在 Fact Guard：模型叙事不符合事实约束时拒绝模型版本，继续使用后端安全事实答案。

但是面试时要说清当前边界：

> **当前 `factualConsistent` 不是一个独立、完整的语义事实核验器，更不能等价成行业标准 hallucination rate。**

`DecisionMetrics.factualConsistent` 默认值本身就是 `true`，因此目前更适合作为系统内部事实安全状态的一部分，而不是单独拿出来证明“幻觉率为 0”。

真正更严谨的生成评测可以补：

```text
Claim extraction
→ 每条 claim 对齐 authoritative fact/evidence
→ Supported / Contradicted / Unsupported
→ 计算 Faithfulness / Unsupported Claim Rate
```

主观自然度、帮助性再考虑 LLM-as-a-Judge。

这类边界如果主动说出来，反而比报一个虚假的“事实一致率 100%”更可靠。

---

## Q13：硬约束怎么评测？

当前 `AiEvaluationService` 里有独立的 hard constraint check，但直接检查范围主要是预算和半径：

```java
if (response.getConstraints().getBudgetPerPerson() > 0
        && item.getAvgPrice() != null
        && item.getAvgPrice() > response.getConstraints().getBudgetPerPerson()) {
    return true;
}

if (response.getConstraints().getRadiusKm() > 0
        && item.getDistanceKm() != null
        && item.getDistanceKm() > response.getConstraints().getRadiusKm()) {
    return true;
}
```

所以面试不能说：

```text
“Evaluator 已经独立校验全部 Hard Constraint。”
```

更准确的说法：

> 生产检索链路会处理城市、行政区、预算、半径等多类约束；离线 evaluator 当前额外独立校验的 hard violation 主要是可直接从 recommendation 字段验证的预算和半径。后续如果做完整约束 Oracle，我会把 city/district/opening-hours 等也独立验证，而不是复用被测代码本身做判断。

最后一句很重要：

> **Evaluator 的 Oracle 最好不要完全复用被测实现。**

否则生产逻辑和评测逻辑同时写错，会出现“错误实现给自己打满分”。

---

# State Machine / Working Memory Evaluation

## Q14：状态机怎么评测？只检查最终状态吗？

不能。

Agent 状态机最危险的是：

```text
最终看起来还能回答
但中间 Canonical State 已经被污染
```

例如：

```text
Turn1：找福州火锅
→ candidatePool 有结果

Turn2：换成杭州日料
→ 理论上搜索域已经变化
→ 旧 candidatePool 必须失效
→ focusedShopId 必须清空
```

如果只测最终回答，很可能漏掉 ghost state。

因此 Conversation Evaluation 每轮都会只读采集 `EvaluationTurnSnapshot`：

```text
workingMemoryVersion
activeCriteria
activeTaskId
taskCount
batchCount
candidatePool
shownShopIds
searchCity
focusedShopId
activeDecisionSessionId
sourceDecisionSessionId
dialogPhase
recommendations
toolCalls
```

然后通过 `expectedTurnStates` 做 post-state 断言。

例如：

```json
{
  "turn": 2,
  "memory": {
    "activeCriteria.cuisine": {"equals": "日料"},
    "candidatePool": {"empty": true},
    "focusedShopId": {"null": true}
  }
}
```

目前路径断言支持：

```text
equals
null
absent
empty
contains
size
```

这实际上是在验证状态机不变量，而不是某个方法有没有被调用。

---

## Q15：为什么还要做“跨 Turn Relation”，逐轮检查状态还不够吗？

有些业务要求不是某一轮的绝对值，而是两轮之间的关系。

例如：

```text
换搜索域
→ candidatePool 必须从非空变为空

换一批
→ 新旧 recommendation 应不重叠

继续问同一家
→ focusedShop 应保持

开始新任务
→ decisionSession 应变化
```

所以 evaluator 支持：

```text
candidatePool:
  INVALIDATED
  PRESERVED

recommendations:
  DISJOINT

focusedShop:
  CHANGED
  PRESERVED

decisionSession:
  SAME
  CHANGED
```

代码类似：

```java
if ("candidatePool".equals(type)) {
    if ("INVALIDATED".equals(relation)) {
        return !from.candidatePool.isEmpty()
            && to.candidatePool.isEmpty();
    }
    if ("PRESERVED".equals(relation)) {
        return from.candidatePool.equals(to.candidatePool);
    }
}
```

这种评测方式很适合有状态 Agent，因为核心正确性经常是：

```text
State(t+1) 是否满足 f(State(t), UserAction)
```

而不是单独判断 `State(t+1)` 长什么样。

---

## Q16：为什么说“Action 执行成功”不等于“状态机正确”？

项目里最典型的经验是：

```text
执行了 BROADEN_FOOD_SCOPE
≠
Canonical Working Memory 一定完成了放宽
```

曾经就出现过：

```text
DecisionSession 已经放宽
Working Memory 仍保留旧 keyword/cuisine
```

如果测试只 Mock 并验证：

```java
verify(service).broadenFoodScope();
```

这个 Bug 完全发现不了。

所以评测原则是：

> **State-changing Action 要检查 post-state，而不是只检查 action identity。**

这也是状态机评测里最值得面试讲的一点。

---

# Tool / Agent Trajectory Evaluation

## Q17：Agent Tool 怎么评测？只看“调用过工具”吗？

不够。

至少分三层：

```text
Tool Selection
→ 该不该调用这个 Tool？

Argument Correctness
→ Tool 参数对不对？

Temporal / Reference Binding
→ 这个 Tool 参数是不是绑定到了正确 Turn 的正确实体？
```

项目当前既有 aggregate `expectedToolNames`，又有按轮 `expectedToolsByTurn`。

按轮可以写：

```json
{
  "turn": 3,
  "tools": [
    {
      "name": "search_shop_evidence",
      "candidateFrom": {
        "turn": 2,
        "ordinal": 2
      }
    }
  ]
}
```

意思是：

```text
Turn3 调 search_shop_evidence
并且 tool.shopId
必须等于 Turn2 推荐列表的第二家
```

这样才能真正验证：

```text
“第二家怎么样？”
```

有没有绑定到用户看到的第二家，而不是只验证“确实调用了评价工具”。

### 当前 Tool Coverage 的一个细节

`expectedToolNames` 的主要语义是 required-tool coverage。只要 required tools 都被覆盖就会 matched；`unexpectedToolCount` 会另外记录。

因此不要把当前 `toolMatched=true` 解释成：

```text
实际 Tool 集与 Expected Tool 集严格完全相等
```

如果要验证严格行为，应使用按轮 Tool 断言，并结合 unexpectedToolCount 分析。

---

## Q18：Reference Resolution 怎么评测？

Reference 是多轮 Agent 特别容易被忽略的一层。

例如：

```text
Turn1：推荐三家
Turn2：第二家怎么样？
```

真正要测的不只是 Route=`BUSINESS_FOLLOW_UP`，还包括：

```text
Context Rewrite 是否应用
candidateOrdinal 是否正确
最终 Tool shopId 是否来自上一轮第二个 Recommendation
focusedShop 是否正确变化
```

项目早期如果把 Ground Truth 写死成某个 shopId，会被动态排序污染。

后来评测器会从真实上一轮 recommendation snapshot 读取 ordinal 对应候选，再检查 rewrite/tool binding。

这是一个很典型的：

> **Dynamic Grounding Oracle。**

Ground Truth 锁定关系，不锁定偶然结果。

---

## Q19：Location 怎么评测？

Location 不能只测“经纬度有没有值”，因为项目里区分：

```text
Device Location
Named Search Location
Administrative Location
POI Resolution
```

所以 Location 类 Case 更关注：

```text
是否进入正确 Route
searchCity 是否正确
最终推荐 Shop 是否属于 expectedCity
切换城市后旧城市 Shop 是否泄漏
明确 Named Location 是否错误回退到 Device GPS
无法解析时是否进入 CLARIFYING，而不是伪造坐标
```

当前 `matchesExpectedCity` 会根据最终 Shop ID 回查商户数据，要求返回商户全部属于 `expectedCity`。

因此它测的是推荐结果的 locality，而不只是“Working Memory 里 city 字段写对”。

---

## Q20：错误恢复怎么评测？

Agent 不可能只测 Happy Path。

Case 可以配置：

```text
expectedErrorCount
expectedRecoveryRoutes
```

例如一轮故意失败，后续用户继续操作：

```text
Turn1 → ERROR
Turn2 → START_DECISION
```

评测要检查：

```text
错误数是否符合预期
发生错误以后 Route 是否恢复到预期轨迹
Working Memory 是否仍然满足状态约束
```

所以“系统没抛异常”不是可靠性评测；真正要看的是：

> **异常发生以后系统是否还能回到合法状态继续工作。**

---

# Outcome vs Trajectory

## Q21：什么是 Outcome Evaluation，什么是 Trajectory Evaluation？

### Outcome Evaluation

只看任务最终结果：

```text
最终状态是否正确
最终商户是否正确
最终回答是否满足业务要求
```

### Trajectory Evaluation

看 Agent 是怎么得到这个结果的：

```text
Route 是否正确
有没有错误改写
Tool 是否选对
Tool 参数是否正确
WM 是否正确变化
有没有错误复用历史候选
有没有先做了非法操作再碰巧得到正确结果
```

两者必须结合。

一个 Agent 最终得到正确答案，但过程里调用了不该调用的写 Tool，在生产系统里仍然是严重错误。

反过来，Trajectory 大体正确但 Retriever 没召回目标结果，也不能算最终任务成功。

项目 Conversation Evaluation 本质上就是在补 Trajectory Evaluation。

---

## Q22：为什么不用 LLM-as-a-Judge 把所有东西都评掉？

因为很多指标本来就有确定答案：

```text
Route 是不是 START_DECISION
Tool 名是不是 search_shop_evidence
shopId 是不是上一轮第二家
candidatePool 是不是失效
预算有没有超过 100
返回城市是不是福州
```

这些问题如果再调用一个 LLM 判断，会引入：

```text
Judge 自身随机性
额外 Token / Cost
Position Bias
Prompt Sensitivity
Judge Model 版本漂移
```

所以项目优先：

```text
Deterministic Rule / State Assertion / DB Oracle
```

LLM Judge 更适合未来评：

```text
回答是否自然
是否真正有帮助
解释是否清晰
语义 Faithfulness
主观完整性
```

即使使用 Judge，也应该：

```text
固定 Rubric
给 Reference / Evidence
与人工标注做校准
对高风险 Case 人工复核
```

### 面试回答

我不会把 LLM-as-a-Judge 当万能评分器。Route、Tool、状态和数据库事实这些可确定验证的指标优先用代码；Judge 只补充难以程序化的自然语言质量。否则等于用一个概率模型给另一个概率模型打分，失败以后连 Ground Truth 都不稳定。

---

# Metrics / Performance / Cost

## Q23：除了正确率，还看哪些工程指标？

Conversation Run 当前聚合：

```text
routeMatchedCount
contextRewriteMatchedCount
toolMatchedCount
toolExpectedCount
toolCoveredCount
localityMatchedCount
finalStatusMatchedCount
shopMatchedCount
unseenRecommendationMatchedCount
avgDurationMs
p50DurationMs
p95DurationMs
p99DurationMs
modelCallCount
modelSuccessCount
modelFailureCount
promptTokenCount
completionTokenCount
```

Decision Evaluation 还聚合：

```text
Recall@K
MRR
Evidence Coverage
Hard Constraint Violation
Factual Consistency
avg / P95 total latency
extracting latency
```

为什么必须同时看质量、延迟和 Token？

因为模型或检索策略升级可能出现：

```text
质量 +1%
P95 +150%
Token +200%
```

工程上未必值得上线。

同样，模型换轻量版可能：

```text
质量几乎不变
Latency -40%
Token -30%
```

这就是有价值的优化。

---

## Q24：P95 为什么比只看平均延迟更重要？

平均值很容易掩盖长尾。

例如：

```text
9 次请求 2 秒
1 次请求 25 秒
```

平均值可能还能接受，但用户每 10 次就可能遇到一次严重卡顿。

因此项目会记录：

```text
P50
P95
P99
```

Decision Evaluation 当前有 `P95TotalDurationMs`，Conversation Run 进一步记录 P50/P95/P99。

Agent 特别容易产生长尾，因为一次请求可能包含：

```text
LLM Rewrite
LLM Planning
Milvus
MySQL
多个 Tool 并发/超时
最终 LLM Narrative
```

只报平均值会掩盖某个外部依赖造成的 tail latency。

---

## Q25：Token 是怎么统计的？字符数估算吗？

不是简单按字符数除以某个系数估算。

项目的模型调用观测会优先读取供应商返回的 usage：

```text
prompt_tokens
completion_tokens
```

或 Spring AI `ChatResponseMetadata.Usage`。

如果供应商没有返回 usage，项目不会把字符数估算成一个“看起来很精确”的 Token 值写进正式评测指标。

这是因为 Token 不只是成本指标，也经常用于模型 A/B。如果估算规则自身误差很大，模型之间的差异可能是测量误差。

---

# Diagnostics / Regression

## Q26：一次评测失败后你怎么定位？

Conversation diagnostics 会按 failure layer 聚合：

```text
route
contextRewrite
toolCoverage
toolArguments
locality
finalStatus
shop
recovery
workingMemory
unseenRecommendations
execution
```

按轮断言失败还会留下：

```text
turnNo
path
assertionType
expected
actual
reason
```

例如：

```json
{
  "turnNo": 2,
  "path": "activeCriteria.cuisine",
  "assertionType": "equals",
  "expected": "日料",
  "actual": "火锅"
}
```

这时已经可以直接判断：

```text
不是 Retrieval 排名问题
而是 Turn2 State Reduction / Mutation 出问题
```

所以 Bad Case 处理顺序通常是：

```text
1. 看 failure layer
2. 看该 Turn route/state/tool trace
3. 定位 authority / contract
4. 重放最小 Case
5. 增加相邻反例
6. targeted regression
7. 阶段结束再 full regression / holdout
```

而不是先改 Prompt。

---

## Q27：怎么做 Baseline 对比？

项目支持两个 Run 的 metric delta。

Decision Evaluation 会检查：

```text
model
Evaluation Dataset Version
caseCount
rankingEvaluatedCount
```

满足可比条件后再比较：

```text
statusMatchRate
constraintMatchRate
completionRate
modelSuccessRate
Token
Latency
Hard Constraint Violation
Factual Consistency
Recall@K
MRR
Evidence Coverage
```

Conversation Evaluation 当前要求：

```text
datasetVersion 相同
caseCount 相同
```

然后比较：

```text
routeMatchRate
contextRewriteMatchRate
toolMatchRate
toolCoverageRate
localityMatchRate
finalStatusMatchRate
unseenRecommendationMatchRate
completionRate
avgDurationMs
```

### 一个当前实现边界

Conversation Compare 当前没有像 Decision Compare 一样强制 `model` 相同。

所以如果拿两个不同模型的 Conversation Run 做对比，虽然 API 允许，但解释结果时必须主动说明模型变量已经变化。

严格 A/B 应显式控制：

```text
Dataset
Case count
业务数据快照
Model
Prompt/Config
Retrieval strategy
外部 Provider 状态
```

只改变实验变量。

---

## Q28：`completionRate` 是不是“用户任务完成率”？

这里必须特别小心。

Conversation Evaluation 的 Run 聚合中：

```java
run.setCompletedCount((int) (results.size() - failed));
```

而 `failed` 是任何 deterministic assertion 失败的 Case 数。

因此 Conversation Compare 里名字叫 `completionRate` 的指标，语义更接近：

> **Evaluation Case Pass Rate。**

它不是严格意义上的线上“用户任务完成率”。

真正和业务最终状态相关的是：

```text
finalStatusMatched
actualFinalStatus
```

面试不要混淆这两个概念。

同样，Conversation Run 的 `errorRate` 当前也是失败 Case 比例，不应直接解释为生产异常率。

这是代码命名和指标语义之间需要注意的地方。

---

## Q29：怎么避免“为了评测分数改 Ground Truth”？

原则有四个：

```text
1. 先判断 Product Contract 是否变了
2. Ground Truth 只在 Contract / 数据事实真的变化时修改
3. 修 Bug 不能同步把失败 Expected 改成 Actual
4. 使用 Holdout 防止对主集过拟合
```

遇到失败 Case，先问：

```text
Actual 错了？
还是 Expected 错了？
```

例如商户数据发生真实变化后：

```text
以前当地没有某类店
现在数据库里新增了真实商户
```

此时 `WAITING_RELAXATION → COMPLETED` 可能是正确的数据漂移，Ground Truth 可以校准。

但如果只是：

```text
模型这次 Route 错了
```

不能为了变绿把 expectedRoute 改成错误 Route。

---

## Q30：线上真实日志怎么回流成离线评测集？

这是目前项目还没有完整自动化实现、但面试非常可能追问的一部分。

比较合理的生产闭环是：

```text
Online Trace / User Log
        ↓
Bad Case Mining
        ↓
去重 + 脱敏
        ↓
Failure Taxonomy
        ↓
人工确认 Product Contract / Ground Truth
        ↓
Candidate Eval Pool
        ↓
Main / Robustness / Holdout 分桶
        ↓
Offline Replay
        ↓
Baseline Compare
```

Bad Case 来源可以包括：

```text
用户立即改写 Query
连续重复提问
用户否定“不是这个”
人工反馈
Tool error / timeout
No-result 高频出现
高延迟 Trace
状态机非法转移
线上新出现的 Location/Reference 歧义
```

不能直接把所有日志都扔进 Dataset，原因是：

```text
重复样本会改变指标分布
脏日志不一定有可靠 Ground Truth
线上流量头部场景会淹没边界 Case
数据可能包含隐私
```

所以真正关键的是：

> **Log → Taxonomy → Curated Eval Set，而不是 Log → 全量回放。**

---

# 常见深挖

## Q31：如果 Recall@K 很高，但最终效果仍然差，怎么排查？

顺序检查：

```text
1. MRR / nDCG
   正确结果虽然召回，但是否排得太后？

2. Context composition
   是否塞入太多噪声证据？

3. Evidence quality
   被召回的信息是否真的支持用户问题？

4. Generator grounding
   模型有没有忽略证据或过度外推？

5. Business constraint
   相关但预算/距离/位置不合法？
```

所以：

```text
Recall 高
≠
RAG 一定好
```

---

## Q32：如果 MRR 提升但 Recall 不变，说明什么？

通常说明：

```text
召回集合没明显变化
但相关结果位置更靠前了
```

这更像是：

```text
Rerank / score fusion 改善
```

而不是 Retriever coverage 改善。

如果 MRR 上升同时 P95 大幅恶化，就要继续判断这次排序收益是否值得额外成本。

---

## Q33：为什么测试 State 不能直接序列化整个 Working Memory 做快照比较？

因为完整 Snapshot 很脆。

新增一个无关字段：

```text
lastDebugInfo
```

都可能让整个 Snapshot 失败，但它和这个 Case 的业务 Contract 无关。

因此项目的 `expectedMemory` 本身就是 minimal assertion，而不是完整 serialized memory snapshot。

更推荐：

```json
{
  "activeCriteria.cuisine": {"equals": "火锅"},
  "candidatePool": {"empty": true}
}
```

只锁定这个 Case 真正关心的不变量。

这能降低评测与内部实现细节的耦合。

---

## Q34：为什么 Robustness Case 不能全靠随机生成？

随机生成适合扩大输入覆盖，但深水区 Agent Bug 往往需要有意识构造历史关系：

```text
A → B → A
先推荐 → 修改搜索域 → 再引用旧 ordinal
先进入 WAITING → 再解释 → 再恢复
先产生 Batch1 → 刷新 Batch2 → 再说“第二家”
```

这种 Case 的价值来自明确攻击某个 invariant。

因此更合理的是：

```text
真实 Bad Case
+
人工设计的 adversarial trajectory
+
有限随机/模型生成 paraphrase
```

而不是只让 LLM 批量造题。

---

## Q35：为什么修完一个 Bad Case 后还要补“相邻反例”？

因为只重放原 Case 很容易得到 case-specific patch。

例如：

```text
Bug：师大被解析错
Patch：contains("师大") 就走某大学
```

原 Case 通过了，但泛化能力更差。

正确做法是紧接着攻击这个方案：

```text
福建师范大学附属小学
其他城市的师大简称
没有 Location Context 的师大
完整大学名称
```

如果方案只对原字符串成立，就不能进入主架构。

所以回归不是：

```text
Bug Case PASS → DONE
```

而是：

```text
Bug Case
+ 相邻反例
+ 原有 targeted regression
→ PASS
```

---

# 当前项目评测体系的真实边界

## Q36：如果让你评价现在这套评测系统，还有哪些不足？

这题不要回答“已经很完善”。目前至少有这些边界：

### 1. Retrieval 指标还不完整

当前有 Recall@K、MRR，但如果要严谨比较整个排序质量，应该补：

```text
nDCG@K
Precision@K / Hit@K
分阶段 Retriever vs Reranker 指标
```

尤其当前 `actualIds` 是最终 Recommendation，Retriever 和 Rerank 的效果还没有完全解耦成独立 benchmark。

### 2. 生成层缺乏独立 Faithfulness Judge

Evidence Coverage 只是 candidate-level evidence 是否非空；`factualConsistent` 也不是完整独立的 claim-level evaluator。

未来可以增加：

```text
Claim-level groundedness
Citation support
Unsupported claim rate
人工 + Judge 校准
```

### 3. Hard Constraint Oracle 覆盖有限

当前独立 evaluator 直接验证预算和半径，其他 hard constraint 仍可以继续扩展独立 Oracle。

### 4. Online Eval 闭环未完整自动化

目前重点仍是：

```text
离线 versioned dataset
+ real-path replay
+ diagnostics
+ baseline compare
```

还没有完整实现线上日志自动采样、标注、Shadow Eval、A/B Gate。

### 5. Conversation Compare 的可比条件还能更严格

目前只强制 datasetVersion + caseCount 相同；未来最好同时保存并检查：

```text
Model
Prompt version
Retrieval strategy version
Data snapshot/version
Tool/provider version
```

### 6. 指标命名需要注意语义

Conversation `completionRate` 实际更接近 case pass rate；`errorRate` 也是 assertion failure case rate，而不是线上 exception rate。

这些边界可以主动讲，因为它证明你真的看过评测实现，而不是只背指标名字。

---

# 一套完整的面试回答

## Q37：面试官直接问“你们 Agent 的评测系统是怎么做的？”怎么回答？

可以按下面这套 2～3 分钟回答：

> 我们没有只做最终答案打分，而是分成 Decision/Retrieval Evaluation 和 Conversation Trajectory Evaluation 两层。单轮决策侧主要验证约束、推荐结果和检索排序，当前有 Recall@K、MRR、Evidence Coverage、部分 Hard Constraint Violation、模型成功率、Token 和 P95 延迟。比如 MRR 是每个 Query 找第一个 relevant shop 的倒数排名再跨 Query 求平均，不是一个 Query 内所有 relevant 的倒数排名求和。
>
> 多轮 Agent 侧我让版本化 JSONL 里的脚本请求走和 Web Console 一样的 `ChatOrchestrationService` 主链路，每轮只读采集 Working Memory、Route、Recommendation、Tool Call 和 Decision Session 等快照。除了 final status，还会验证每轮状态和跨轮 relation，例如改了搜索域以后 candidatePool 必须失效、focusedShop 要清掉，换一批的新推荐要和旧 Batch 不重叠。
>
> Tool 也不是只测有没有调用，而是分 Tool Selection 和 Argument Correctness。像“第二家怎么样”这种 Case，评测器会从上一轮真实 Recommendation Snapshot 取第二家的 shopId，再要求这一轮 Tool 参数绑定到那个 shop，而不是把某个固定商户 ID 写死。
>
> 数据集分 main regression、robustness 和 holdout。Main 保主流程，robustness 放状态切换、历史引用、错误恢复等深水区 Case，holdout 用来防止我们针对已知 Case 过拟合。每次 Run 会保存 dataset version 和 Git commit，再提供 baseline compare 和 diagnostics。
>
> 评测失败后我不会直接调 Prompt，而是先做 failure-layer decomposition，看是 Route、Working Memory、Tool、Location、Retrieval 还是 Generation。能确定验证的地方优先 deterministic grader，LLM-as-a-Judge 只适合未来补自然度、帮助性、Faithfulness 这类难以程序化的指标。
>
> 现在这套体系也有边界，比如 Retrieval 还没独立补 nDCG，Evidence Coverage 不能等价于 Faithfulness，Hard Constraint 独立 Oracle 目前主要覆盖预算和半径，线上日志到离线 Dataset 的自动化闭环也还没完全做。这些是后续评测体系继续扩展的方向。

---

# 牛客 / AI 应用岗常见评测追问题单

下面这些问题最好全部能回答，不要求逐字背，但要能联系项目：

1. RAG 怎么评测？为什么必须拆 Retriever / Generator / End-to-End？
2. Recall@K、Hit@K、Precision@K、MRR、nDCG 分别解决什么问题？
3. MRR 为什么只看第一个 relevant？多个 relevant 怎么处理？
4. Recall 很高但效果差怎么办？
5. Rerank 怎么单独做 A/B？
6. Ground Truth 从哪里来？谁来标？
7. LLM 输出不稳定，离线评测怎么保证可复现？
8. 为什么不能只用 LLM-as-a-Judge？
9. LLM Judge 有什么偏差？怎么和人工标注校准？
10. Agent 的 Outcome Evaluation 和 Trajectory Evaluation 有什么区别？
11. Tool Calling 怎么测？Tool Selection 和 Argument Correctness 怎么拆？
12. 多轮状态机怎么测？为什么不能只看最终状态？
13. Working Memory 怎么防 ghost state？评测如何发现？
14. Reference / ordinal 这类动态对象怎么构造 Ground Truth？
15. Error Recovery 怎么测？
16. Location / 外部 Tool 不稳定会不会让评测不稳定？怎么隔离？
17. Main / Robustness / Holdout Dataset 分别是什么作用？
18. 怎么防止工程师为了指标修改 Ground Truth？
19. Bad Case 怎么进入 Regression Dataset？
20. 海量线上日志怎么压缩成有限但高价值的离线 Case？
21. 如何避免 Dataset Overfitting？
22. 模型 A/B 时要控制哪些变量？
23. 为什么除了平均延迟还要看 P95/P99？
24. Token 和 Cost 怎么统计？
25. 如果两个策略 Recall 一样，但 MRR 不同，你怎么解释？
26. 如果 MRR 提升但延迟翻倍，你会不会上线？
27. Evidence Coverage 和 Faithfulness 有什么区别？
28. 怎么真正测 hallucination / unsupported claim？
29. 你们当前 evaluator 哪些指标是不完善的？
30. 如果重新设计下一版评测平台，你首先会补什么？

最后一题可以回答：

```text
第一优先：把 Retrieval / Rerank 分阶段 Ground Truth 和 nDCG 补齐
第二优先：补 claim-level groundedness / factuality evaluator
第三优先：让线上 trace → bad-case mining → curated dataset 形成稳定闭环
第四优先：把 model/prompt/retrieval/data/provider version 全部纳入 run manifest
```

这样评测体系就会从“离线回归工具”进一步演进成真正的 Agent Quality Platform。
