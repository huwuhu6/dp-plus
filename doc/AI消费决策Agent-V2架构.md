# AI 消费决策 Agent V2 架构契约

## V1 根因与 V2 总览

V1 把 Context Rewrite、Route、criteria reduction 与 policy 判断串在同一 pipeline；`ContextRewriteResult`、route 和 `DecisionConstraints` 混合了用户语义、系统推断与执行指令，Task 还会由地点/菜系签名猜测。V2 将“用户表达什么”和“系统如何执行”分开。本阶段新增独立领域核，不接入运行主链，不提供 V1 Adapter。

```text
User Turn -> SemanticInterpreter -> TurnSemantics
          -> EffectiveTaskContextResolver -> GroundingResolver -> GroundedTurn
          -> PreExecutionReducers -> OCC Commit -> ExecutionPlanCompiler -> ExecutionPlan
          -> Executor -> ResultVerifier -> PostExecutionReducers -> OCC Commit
          -> ResponseSpec -> Closed-world Renderer
```

## State ownership 和语义模型

| State | Owner | 时机 |
| --- | --- | --- |
| 用户明确 requirement、feedback、Task lifecycle | PreExecutionReducer | 语义确定后 OCC commit |
| RecommendationBatch、焦点实体、条件执行效果 | PostExecutionReducer | 执行/验证完成后 OCC commit |
| Decision evidence | batch 可解释周期 | 仅保存轻量 DecisionReason |
| Runtime trace | telemetry/debug | 短生命周期，非 Working Memory |

Task 是持续的消费目标，不是 city/cuisine tuple；这些都是可演进 criteria。`TurnSemantics` 固定为 `TaskDirective`、强类型 `RequirementChange`、`EntityFeedback`、稳定 `UserRequest`、受限 `SemanticRelation` 和独立 `references` operand 集合。lifecycle 仅有 CONTINUE / START_NEW / RESTORE / ABANDON；修正和细化由 requirement change 表达。

Reference 是 operand，不是 operation。未绑定时可用 ordinal/focused/named/task/historical/POI/cuisine reference；绑定后只能成为 `ShopIdentity(shopId,batchId,ordinal)` 或 `TaskIdentity(taskId)`。LLM 不得生成 shopId、taskId、plan、mutation 或 tool 的数据库 identity。解析 `TaskRef` 仅确定当轮 `EffectiveTaskContext`，绝不改 durable `activeTaskId`；PreReducer 的 RESTORE 才能提交切换。

`CRITIQUE` 不等于 `REJECT`：前者只是维度不满，后者才 task-scoped 排除。批次级“都不喜欢”是 `BatchFeedback`，不得展开成 N 个 reject。

## Criteria、preference 和 capability

`DiningCriteria` 是 Location、Cuisine、Budget、Distance、DiningTime、SemanticPreferences 的强类型组合，不使用 Field/Object 或换皮 EAV。Budget 支持 `softTarget=100, hardMax=150`，也能 clear。`RelativePreference(PRICE, LOWER)` 表达“便宜一点”；它不能落成用户绝对预算，未来 search strategy 的推导值也不能回写 user requirement。

用户 importance（MUST/PREFER）和系统可验证性分离。后者为独立 `CriterionCapability`：DETERMINISTIC / EVIDENCE_BASED / UNVERIFIABLE；例如 QUIET 是 capability metadata，不是 `quiet=true` 用户状态。

## ExecutionPlan、Adaptive 与 Verifier

`ExecutionPlan` 是有 group/action 上限的平面 DAG。Group 带依赖、可选 guard 和并行 actions；guard 仅匹配前序 request observation。没有嵌套 plan、循环、脚本或任意 boolean AST。`ExecutionPlanCompiler` 是纯函数：没有 LLM、tool client、数据库 lookup 或 WorkingMemory writer。

Executor 仅返回 observation、evidence 和 `DomainEffect`；不可直接写 memory，PostReducer 才能经 OCC 持久化。STATIC_PLAN 的标准是执行前能否穷举所有合法后继 action（包括已知条件分支和多事实并查），不是问题是否“复杂”。未知的“必要时继续查”进入只读 ADAPTIVE_RESEARCH：entity whitelist、只读工具、max steps/tool calls/deadline、no-progress/duplicate stop，并仅返回 `EvidenceBundle`。

`ResultVerifier` 仅做 deterministic hard check 和 evidence coverage，输出 `VerificationReport`；不得改 state、requirements、Task、排序、放宽或无限重搜，不能成为第二个 PolicyEngine。

## Evidence、失败与幂等

RecommendationBatch/Candidate 只持久化 `DecisionReason(reasonType, criterionRef, observedValue, evidenceRef)`，不保存完整 Milvus trace 或 rerank feature。编译失败（未绑定 reference、非前序 guard、超预算）为确定性失败；工具失败是 observation；OCC 冲突由 reducer 重读策略处理。

V2 runtime 复用既有 `IdempotencyService` 与 `ai_idempotency_record` 的 `(userId, chatId, scope, idempotencyKey)` 唯一语义；它已保存 request hash、PROCESSING/SUCCEEDED、结果 JSON/reference。OCC 不覆盖 `T1 -> T2 -> retry(T1)` 的幂等问题，但不新增第二张 TurnExecutionRecord 表。

## V2.0 不支持

- 完整 stakeholder constraint model；
- 完整 temporal scope algebra；
- 任意复杂 conditional workflow；
- multi-agent；
- generic workflow engine。

## 运行主链切换

`ChatOrchestrationService` 现在仅保留 HTTP/application 兼容签名，实际请求无条件进入
`V2ChatOrchestrator`；同步、SSE 和 conversation evaluation 因而使用同一入口。V2 编排以
`WorkingMemoryVersionService.append` 完成 pre/post 两次 OCC：pre commit 后产生冻结的
`PlanningSnapshot`，post commit 成功前不得渲染新 RecommendationBatch。post conflict 会从最新
版本完整 replan 一次；再次冲突只返回可重试的降级结果，绝不泄露未提交候选。

持久 Task 中的 `v2Criteria`、relative preferences、relaxable/locked、rejected entities、selection
和 SearchAnchor 为 V2 写路径的 canonical state。旧 `DecisionConstraints` 只通过
`V2CriteriaProjection` 向尚未迁移的确定性检索基础设施作单向投影；V2 runtime 不读取或写回它。

`V2ActionHandler` 是 SearchSpec 到既有 MySQL/Milvus 决策检索和实体事实工具的受限适配层。
它不接收 raw user text、WorkingMemory 或 durable writer。`ResponseSpec` 是 closed-world 输出，
renderer 只做展示投影。

运行时加固补充：TaskDirective 只是单轮命令；Task durable state 使用封闭的 `TaskLifecycle`
（ACTIVE/SUSPENDED/COMPLETED/ABANDONED），唯一由 `TaskLifecycleReducer` 改写 activeTaskId 和
task lifecycle。V2 Search 改为通过无状态 `ShopRetrievalEngine`，它不依赖 AiDecisionSession、
DecisionTransition、消息持久化或 WorkingMemory writer。SearchSpec 的 PRICE LOWER、ALTERNATIVES
临时可见批次排除、SIMILAR anchor 要求都在该边界消费。

ExecutionObservation 增加封闭 typed value；StaticPlanExecutor 对 Boolean/Numeric/Category guard
按类型比较，绝不从展示文本推断事实。SemanticInterpreter schema 已包含 typed relations 与
conditionalRequirementChanges，条件菜系 fallback 可由已有静态 DAG 和 PostReducer 落地。

## Evaluation 与迁移

领域核 golden tests 不依赖模型、数据库或时间，覆盖复合 critique/fact/alternatives、条件选店、static/adaptive、历史 Task grounding、batch feedback、critique/reject、预算和相对偏好、松弛/锁定。主链切换后将 fixture 接入 JSONL conversation evaluation，并运行 `mvn -q test` 和已登录轨迹评测。

| V1 component | 状态 |
| --- | --- |
| `ChatPipeline`, `ChatOrchestrationService` | KEEP TEMPORARILY；next phase 由 V2 主链替换 |
| ContextRewrite / IntentRouting / CriteriaReduction / PolicyGuard nodes | REPLACE NEXT PHASE |
| `ContextRewriteResult`、legacy route/TurnPlan semantics | DELETE AFTER CUTOVER |
| `DecisionConstraints`、`ConversationWorkingMemory`、`WorkingMemoryVersionService` | KEEP TEMPORARILY；迁移持久投影时复用 OCC 语义 |
| V1 RecommendationBatch / candidate DTO | KEEP TEMPORARILY；迁移为 evidence-aware projection |

Phase 1.5 收敛：`CriteriaPatch` 的 null 仅表示 untouched，clear 使用封闭 `ClearedCriterion`；条件 requirement 独立建模且不进入 PreReducer。`ObservationPredicate` 为 Boolean/Numeric/ResultState/Category 的封闭类型；TaskRef 使用结构化 selector，Ordinal 只读取 `TaskView.currentVisibleBatch`，引用失败返回 typed clarification。`CompilationResult` 显式区分 Direct/StaticPlan/Adaptive；SearchAction 持有由 committed `PlanningSnapshot` 冻结的 SearchSpec，绝不在执行时重新读取 WorkingMemory。

Java 21 sealed interface/record 为封闭领域模型提供编译期分支约束和不可变载体，均无需 preview；参见 [Oracle Java 21 language changes](https://docs.oracle.com/en/java/javase/21/language/java-language-changes-summary.html)。将来 group 内并发执行使用正式 `CompletableFuture` 或项目既有执行抽象，避免 preview API；参见 [Java 21 CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)。
