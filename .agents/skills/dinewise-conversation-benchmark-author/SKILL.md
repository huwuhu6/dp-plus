---
name: dinewise-conversation-benchmark-author
description: 为 DineWise AI 消费决策 Agent 设计、生成并审计高质量多轮对话 Benchmark 与回归 Case；用于真实 Failure Case 回流、conversation evaluation 扩展、robustness/holdout 数据集建设。普通功能开发或仅讨论评测时不创建用例。
---

# DineWise Conversation Benchmark Author

这是供 Codex 使用的项目级 Skill。目标不是机械增加 Case 数量，而是把真实业务失败沉淀为可重复、可解释、可审计的多轮评测，并回答：

> 如果这个 Case 通过，它究竟证明了 Agent 的哪个业务能力？如果失败，失败发生在用户语义理解、Task/Criteria 状态、Grounding、执行结果，还是评测本身？

本 Skill 只负责评测设计、Case authoring 与必要的最小评测 fixture 调整；除非用户明确要求，不因为新增 Case 顺手修改 Runtime 让测试通过。

## 开始前必须读取

每次使用都重新读取当前 `main`/当前开发分支，不能依赖 Skill 创建时的实现和 Case 数量：

- `doc/AI消费决策Agent-V2架构.md`
- `doc/AI消费决策Agent开发记录.md`
- `src/main/java/com/hmdp/ai/service/AiConversationEvaluationService.java`
- `src/main/java/com/hmdp/ai/service/ConversationEvaluationDatasetLoader.java`
- `src/main/java/com/hmdp/ai/entity/AiConversationEvaluationCase.java`
- `src/main/resources/eval/datasets/*.jsonl`
- 与目标 Capability 直接相关的 V2 domain/reducer/runtime/tests

V2 新 Case 优先使用 `expectedV2Outcomes` 表达业务 Outcome，不以 legacy route、内部类名、固定工具顺序或最终自然语言文本作为主要 Oracle。

## Gate 1：先定义 Capability Claim

新增 Case 前先写清：

1. **Capability Claim**：要证明哪个业务能力。
2. **Positive Proof**：什么稳定、可观察 Outcome 足以证明它。
3. **Negative Meaning**：失败意味着什么，不意味着什么。
4. **Out of Scope**：本 Case 明确不验证什么。

Claim 必须写在业务机制层，不绑定实现。例如：

- 好：用户说“再便宜一点”时，只形成相对价格偏好，不伪造绝对预算。
- 好：从“福大附近火锅”切换到新的约会西餐任务时，旧 Task 条件不能污染新 Task。
- 好：恢复历史方案时应恢复目标 Task，而不是重新从聊天文本猜一套条件。
- 差：必须调用 `TaskLifecycleReducer`。
- 差：第二轮必须命中某个 Java 方法。

若只能通过内部实现细节证明成功，先重设计 Oracle，不新增 Case。

## Gate 2：Existing Suite Audit 与 Coverage Gap

新增前扫描全部 JSONL，用临时 matrix 检查已有覆盖。至少关注：

- Task：CONTINUE / START_NEW / RESTORE / ABANDON
- Criteria：继承、覆盖、显式 clear、复合条件
- Relative Preference：如“便宜一点”，不得误写成绝对值
- Location/SearchAnchor：设备位置、显式目的地、行政区、POI、“我附近”恢复
- Reference：当前候选、序号、命名商家、历史 Task、历史 Batch
- Feedback：critique / reject / batch feedback
- Result：COMPLETED / WAITING_RELAXATION / UNSUPPORTED / clarification
- Candidate invariant：换一批不重复、旧候选不泄露、硬条件不被绕过
- Concurrency/state：需要时验证 OCC 后可观察结果，不绑定内部重试次数

只替换城市、菜系、数字或同义措辞，但没有改变 Observation、状态迁移、业务 invariant 或合法 Action 的 Case，不得宣称新增 Capability。它可以作为 robustness 变体，但数量应与能力覆盖分开统计。

## Gate 3：Internal Oracle 与 Counterfactual

先确定正确业务 Outcome，再写用户 turns。关键能力优先设计容易混淆的对照：

- “预算改成 80” vs “再便宜一点”：绝对 Criteria mutation vs relative preference。
- “换成粤菜” vs “算了，换成约会吃西餐”：当前 Task refinement vs 新消费目标。
- “重庆附近” vs “我附近”：显式目的地 vs 当前设备位置。
- “这家有点贵” vs “不要这家”：critique vs reject。

Counterfactual 不是为了凑双倍数量，而是证明评测能区分真正决定行为的语义差异。没有自然对照时可以不强凑，但要缩窄 Claim。

## Gate 4：Oracle 检查业务 Outcome，不逐字检查回答

多轮 Agent 的自然语言输出具有随机性。除非测试目标就是展示格式，否则不要逐字比较最终答案。

优先按 turn 断言：

- active Task / lifecycle；
- 用户明确 Criteria 的保留、替换或 clear；
- relative preference 是否仍为相对偏好；
- SearchAnchor/位置来源；
- 不该变化的状态是否保持不变；
- hard constraints 是否被验证；
- 候选集合关系，如换一批不重复；
- 必要业务 Action 是否发生；
- failure / unsupported / clarification 是否进入正确受控状态。

断言必须最小化，只检查 Claim 必需字段。不要序列化完整 Working Memory 做快照比较，也不要把无关字段绑死。

## Gate 5：多轮 Case 必须验证关键中间状态

不要只看最终一轮。2~N 轮脚本应根据 Claim 检查关键 turn：

- 第 1 轮建立了什么 Task/Criteria/Anchor；
- 第 2 轮到底继承、修改、clear 还是 START_NEW；
- 第 3 轮引用的是当前结果还是历史结果；
- 后续状态是否被 stale context 污染。

失败诊断应能落到 `turnNo + path + expected + actual`，而不是只有一个总分。

## Gate 6：DEV / Robustness / Holdout 分工

- **DEV/Regression**：来自真实 bug、架构变更和已知边界，可反复用于开发。
- **Robustness**：保持 Capability 不变，改变自然语言表达、条件组合、上下文位置或边界输入，检验脆弱规则和语义稳定性。
- **Holdout**：开发时不得针对具体 Case 调 Runtime；至少改变一个真正影响观察或推理的维度。看过 Holdout 结果后若修改 Runtime，该批 Holdout 已被消费，不能继续作为“未见数据”证明泛化。

不要把 20 条同义改写当成 20 个独立 Capability。

## Gate 7：用户话术不得泄露答案

`turns` 只包含真实用户可能提供的信息：自然语言、必要的位置等上下文。不得写入：

- Task/route/reducer 等内部名称；
- expected status、expected label；
- “请先调用某工具”；
- “必须清除某字段”；
- 固定 id、实现阈值或测试专用暗号。

如果不泄露答案就无法判断预期行为，说明业务 contract 或 fixture 不够清楚，应补证据或缩窄 Claim。

## Gate 8：防止 Benchmark 迎合当前实现

写完 Case 后反向审计：

- 换一种合法实现，这个 Case 是否仍然成立？
- verifier 是否只认固定类名、方法、工具顺序或 patch？
- Runtime 业务行为错误时是否仍可能通过？
- Case 测的是业务 invariant，还是作者知道的内部路径？

只能证明“当前代码按当前方式运行”的 Case，不算有效业务 Benchmark。

## Gate 9：生成与 Deterministic Validation

用户明确要求生成 Case 时，先完成上述 Gate，再写 JSONL。生成后至少验证：

- JSONL 每行可解析；
- `caseCode` 唯一；
- datasetVersion 与 Loader contract 一致；
- assertion path/operation 被当前 matcher 支持；
- 对应 dataset loader / evaluation service 单测通过。

除非用户明确要求，不自行消耗外部 LLM 配额执行完整动态评测。未运行就是 `NOT RUN`，不能写成 PASS。

V2 Case 结构示意：

```json
{
  "caseCode": "V2_RELATIVE_PRICE_EXAMPLE",
  "datasetVersion": "...",
  "active": true,
  "notes": "验证相对价格偏好不会污染绝对预算。",
  "turns": [
    {"message": "推荐一家餐厅"},
    {"message": "再便宜一点"}
  ],
  "expectedV2Outcomes": [
    {"turn": 1, "assertions": {"criteria.budget": {"null": true}}},
    {"turn": 2, "assertions": {
      "relativePreferences": {"contains": {"dimension": "PRICE", "direction": "LOWER"}},
      "criteria.budget": {"null": true}
    }}
  ]
}
```

示例只说明 contract。真正 authoring 时必须按当前 schema、matcher 和 Runtime 能力重新核对。

## 真实 Failure Case 回流

发现 Agent bug 后按以下顺序沉淀：

1. 保留原始业务语义，去掉无关隐私与噪声。
2. 压缩成能稳定复现的最小多轮脚本。
3. 写清错误行为与正确业务 invariant。
4. 审计已有 Case；已有 Case 未失败时优先检查 Oracle 是否太弱。
5. 若任务包含修复，先补能暴露问题的回归 Case，再修 Runtime。
6. 修复后跑定向测试和相关回归集。
7. 将 Case 归类到对应 Capability，而不是只记录“某次 bug”。

## 数量、覆盖与对外结论

报告时必须区分：

- Case 总数；
- Capability/业务场景覆盖；
- DEV / robustness / holdout 数量；
- deterministic tests；
- 动态真实模型评测是否运行；
- 已知不稳定因素。

禁止把“100+ Case”等价成“100+ 独立能力”或“生产级正确率”。

可以对外写“累计沉淀 100+ 多轮场景/回归用例”，前提是**当前仓库重新统计后的实际数量确实达到该值**；不要写“100+ 线上真实 Case”，除非数据来源确实如此。只有真实模型评测实际运行并保存结果时，才能报告通过率、准确率或模型提升百分比。

## 最终质量清单

- [ ] 用户话术真实，不泄露答案或内部实现。
- [ ] Capability Claim 能用一句业务语言说明。
- [ ] Existing suite 无等价 Case，或明确它只是 robustness 变体。
- [ ] Oracle 检查稳定业务 Outcome，而非随机自然语言文本。
- [ ] 多轮问题检查必要的中间 turn。
- [ ] 断言最小，不绑定完整 Working Memory 或固定实现路径。
- [ ] 已考虑 Counterfactual 或边界行为。
- [ ] 换一种合法实现后 Case 仍成立。
- [ ] JSONL/schema/matcher contract 已验证。
- [ ] 没有把 `NOT RUN` 写成 `PASS`。
- [ ] Case 数量和 Capability 覆盖分开报告。

Skill 本身不是 Runtime 功能，也不是评测成绩。它的价值是约束 Codex 如何构造与审计 Benchmark，避免为了数量堆同义 Case、泄露标准答案或把实现细节误当成业务正确性。