# Agent 评测——简历驱动追问链

## 0. 如果简历要写这一点，怎么写

建议简历 bullet：

> 建设多轮 Agent Trajectory Evaluation，复用真实 ChatOrchestrationService 主链执行脚本化对话，除最终回答外按轮校验 Route、Tool、Working Memory、Location 等业务 Contract，并通过 Robustness / Holdout 数据集定位状态与工具链回归、降低 Case Chasing 风险。

这句话主动暴露的关键词只有：Trajectory Evaluation、真实主链、业务 Contract、Robustness / Holdout。不要在同一条 bullet 里再塞 Recall、MRR、Evidence Coverage、JSONL、Diagnostics 等所有细节。

---

## 1. 面试官第一问：为什么不能只看最终回答？

### 面试官为什么会问

普通 LLM 应用最常见的评测就是“最终答案好不好”。你需要证明：有状态 Agent 的错误可能发生在中间轨迹，而且最终文本看起来对并不代表过程正确。

### 40 秒回答

> 多轮 Agent 的最终文本可能碰巧正确，但中间状态已经错了。比如 Route 选错但模型自己补出了合理回答，Tool 调错但结果碰巧类似，Working Memory 被污染但这一轮没有暴露。所以我把评测拆成 Outcome Evaluation 和 Trajectory Evaluation：除了最终状态，还按轮采集 Route、Tool、Working Memory、Location、Reference 等快照，用 deterministic contract 判断每一层是否符合预期。这样 Case 失败后能继续定位责任层，而不是只得到一个总分。

### 面试官继续

- 具体一条 Case 检查哪些东西？
- 每轮状态怎么拿？
- 评测是否走真实生产链路？

---

## 2. 一条 Conversation Case 到底怎么跑？

### 核心流程

```text
加载 dataset case
→ 创建独立 eval chatId
→ 按 turns 顺序调用真实 ChatOrchestrationService.chat()
→ 每轮采集 response / WM snapshot / Tool Call / model trace
→ deterministic assertions
→ 持久化 CaseResult
→ 聚合 Run 指标
```

关键点是：评测不是自己写一套假的 Agent，而是复用真实主入口。这样可以避免“单测通过，但生产 wiring 根本没走这条路径”。

### 面试官可能反驳

> 这不就是集成测试吗？

回答：它确实包含 integration / end-to-end 的性质，但目标不仅是接口是否成功，而是对多轮 trajectory 的业务 Contract 做结构化断言，并保存可比较的 Run 指标与 diagnostics。

---

## 3. Ground Truth 怎么设计？LLM 有随机性怎么办？

这是最值得讲的设计点之一。

### 早期容易犯的错

把 Ground Truth 绑定到模型某个偶然中间字段，例如要求某轮 LLM 必须把 cuisine 恰好抽成某个字符串。模型只要换一种等价表达，业务行为正确也会判失败。

### 后来的原则

> Golden Dataset 应锁定稳定业务 Contract，而不是锁定概率模型偶然中间产物。

例如“第二家怎么样”不要把某个固定商户 ID 永久写死，更稳的断言是：

```text
本轮 Tool.shopId
=
指定上一轮 Recommendation Snapshot 的第 2 个 shopId
```

这样测试的是 ordinal binding，不是偶然排序结果。

### 面试官可能继续

> 哪些东西适合 deterministic grader，哪些需要 LLM Judge？

当前项目优先对可确定的状态、Route、Tool、ID、Location、版本投影使用 deterministic grader。开放文本质量可以考虑 LLM Judge，但不能把所有确定业务规则再交给另一个模型猜。

---

## 4. Robustness、Conversation、Holdout 分别干什么？

### Conversation 主回归

覆盖常见多轮主流程，保证日常开发不破坏核心功能。

### Robustness

故意打脆弱边界，例如：

```text
A → B → A 历史任务切换
条件变化后 candidate projection 是否失效
旧 ordinal 是否绑定到新候选
换一批是否重复已展示商户
Reference + Mutation compound turn
Location 变化后旧地区候选是否泄漏
No-result 后能否正确恢复
```

### Holdout

用于防止为了已知 Case 反复特调。它最大的价值不是“多一套题”，而是判断修复是否具有一定泛化性。

### 面试官可能问

> 既然 Holdout 最重要，为什么不天天跑？

回答：Holdout 如果频繁参与针对性调试，也会逐渐变成训练集。日常主要跑 targeted / main regression，阶段性封板再看 Holdout 是否退化。

---

## 5. Complete 为什么看起来不高？

这是高风险问题，必须先解释 metric semantics 再报数字。

Complete 是多个 contract 的 AND，不是简单 accuracy。例如：

```text
Route 对
Tool 对
Final 对
WM 对
但 unseen recommendation 断言失败
→ Complete 仍然 false
```

所以不能直接把 `Complete / Cases` 当“系统可用率”。应该拆 Route、Tool、Final、Locality、WM、Reference 等指标，并结合 failure clustering 看具体责任层。

### 面试策略

不要主动先甩一个低 Complete 比例。先说明：

> 我们的 Complete 是 trajectory contract 的 AND，因此我不会把它包装成简单准确率；我更看各子指标和失败聚类。

面试官继续要数字，再给归档 Run。

---

## 6. 为什么不继续修到 100%？

这是很能体现工程判断的一题。

### 真实问题

后期会出现：

```text
修一个 Case
→ 增加局部规则
→ 新 Case 击穿
→ 再加规则
```

Location 里“师大”噪声多时曾尝试 UNIVERSITY hard filter，但“福建师范大学附属小学”马上证明这种 taxonomy gate 会误杀。如果继续补 PRIMARY_SCHOOL、MIDDLE_SCHOOL、HOSPITAL……生产代码会越来越像在拟合评测集。

### 核心回答

> Evaluation 的目标是发现系统性缺陷，不是诱导代码把 Dataset 背下来。我的停止标准是核心 unit / targeted invariant 全绿，主回归没有系统性退化，Holdout 不退化，新增失败能解释且没有模型调用异常。出现明显 case chasing 时，宁愿记录边界也不继续堆特殊规则。

---

## 7. Recall@K / MRR 和 Agent Trajectory Evaluation 是什么关系？

这是两层不同的问题。

### Decision / Retrieval Evaluation

关心检索和排序：

```text
Recall@K
MRR
Evidence Coverage
Hard Constraint Violation
Latency / Token
```

### Conversation / Agent Trajectory Evaluation

关心多轮状态和执行：

```text
Route
Tool
Final Status
Locality
Working Memory projection
Context Rewrite
Reference binding
Task switch / recovery
```

同一个系统需要两层评测，因为“没召回正确店”和“引用绑定错店”虽然最终都可能回答错，但修复责任完全不同。

---

## 8. MRR 到底怎么算？

面试官如果单独追指标，要讲准确。

单个 Query 先取第一个 relevant result 的 reciprocal rank：

```text
结果：[错, 错, 对, 对]
第一个 relevant 在第 3 位
RR = 1/3
```

然后跨 Query 求平均得到 MRR。

一个 Query 即使有多个 relevant document，RR 也只看第一个相关结果的位置；它和 Recall@K 的“召回了多少 relevant”不是一个维度。

---

## 9. Dataset 为什么用 JSONL，而不是只放数据库？

### 面试官可能问

> 评测 Case 为什么不直接存在 MySQL？

JSONL 的主要价值是把 Dataset 当代码资产：

```text
Case 变更可以 Code Review
Dataset Version 和 Git Commit 可以一起追踪
分支之间可以复现同一 Ground Truth
避免本地数据库漂移导致结果不可比
```

当前 Loader 可以优先读取 classpath versioned JSONL，必要时保留旧 MySQL fallback。

---

## 10. 评测失败以后怎么定位，而不是只看红绿？

先做 failure decomposition：

```text
Route 错
→ Turn Understanding / routing

WM projection 错
→ mutation / reducer / task state

Tool 错
→ tool selection / argument grounding / state guard

Location 错
→ administrative / POI resolution

Retriever 错
→ hard filter / embedding / TopK / threshold

Final 文本错但前面都对
→ evidence assembly / generation
```

因此评测系统要保留 stage trace、Tool Call、Working Memory snapshot、diagnostics，而不是只存一个 pass/fail。

---

## 11. 你怎么证明不是为了评测数据改 Ground Truth？

### 回答重点

- Dataset 版本化；
- Case 变更可以审查；
- Holdout 尽量不参与日常特调；
- 修复优先改生产 invariant，不随意改 expected；
- 如果 Ground Truth 本身不稳定，要说明为什么 Contract 需要重定义，而不是为了提分偷偷改答案。

真正重要的是：Ground Truth 也必须接受审查，但不能和生产代码一起为了某个红 Case 同时往“能过”为目标修改。

---

## 12. 这条线最后怎么收口

> 我们没有把 Agent 评测等同于“最后回复像不像标准答案”。Decision/Retrieval 层单独看 Recall、MRR、硬约束和 evidence；Conversation 层则让脚本化多轮请求走真实主链，逐轮检查 Route、Tool、Working Memory、Location、Reference 等 contract。Ground Truth 尽量锁稳定业务 invariant，Holdout 用来观察是否出现 case chasing。这样一个 Case 失败后能定位是状态、工具、检索还是生成问题，而不是只看到一个总分。

---

## 13. 当前不能吹什么

- 不能把 Complete 当成简单系统准确率；
- 不能说所有指标都已经实现，Precision@K、nDCG、完整 LLM Judge Faithfulness 等不能冒充现有能力；
- 不能说 Holdout 能证明“泛化能力已经充分解决”，它只是降低已知 Dataset 过拟合风险；
- 不能说 deterministic grader 能评所有开放生成质量；开放文本仍需要人工或模型评审补充；
- 评测集规模仍有限，当前价值主要在回归、定位和工程约束，不是学术 benchmark。

---

## 14. 开发记录里最值得讲的一次评测系统升级：从“最终断言”变成“按轮状态断言”

早期 Conversation Evaluation 已经能检查最终 Route、最终 Working Memory、Tool 聚合和推荐去重，但对“哪一轮开始错”仍然不够敏感。后来 JSONL Case 增加：

```text
expectedTurnStates
expectedToolsByTurn
expectedRelations
```

每轮请求结束后只读采集 Working Memory projection、version、candidatePool、focusedShop、sourceTask、response recommendations 和本轮 Tool Call，再做结构化断言。

第一阶段支持的状态断言包括：

```text
equals / null / absent / empty / contains / size
```

跨轮关系则可以检查：

```text
candidate pool 是否失效/保留
recommendation 是否不重叠
focused shop 是否变化/保留
task 是否相同/变化
```

这让 Diagnostics 不再只说“Case 失败”，而是能给出失败 turn、JSON path、operator、expected 和 actual。

### 面试官问：为什么这比最终 WM 断言更重要？

因为最终状态正确也可能是中间错过又被后续轮次碰巧修回来。Trajectory Evaluation 要检查状态演进过程，而不只是终点。

---

## 15. 为什么从 MySQL Case 迁到 Git 版本化 JSONL？开发记录里的真实原因

旧评测用例通过 V23～V51 多个 Migration 持续 INSERT/UPDATE，主要问题不是“SQL 难写”，而是 Dataset 本身无法很好参与软件工程流程：

```text
变更不易 Code Review
不同环境数据库可能漂移
分支难以绑定同一 Ground Truth
新增 Case 需要再写 Migration
```

迁移后 `ConversationEvaluationDatasetLoader` 优先读取：

```text
src/main/resources/eval/datasets/{datasetVersion}.jsonl
```

文件不存在时仍 fallback MySQL，避免一次性破坏旧环境。最初迁移了 conversation-v1 40 条，后续补齐 holdout 16 条和 robustness 数据集。

这次迁移还真实踩过两个集成问题：混合数组 DTO 类型声明不匹配导致 Jackson 反序列化失败；JSONL 使用虚拟 ID 后，Diagnostics 仍按数据库 ID 查询导致 caseCode 对错用例。两个问题最终都通过让 Loader 成为 Dataset authority 收口。

这类细节很适合回答“你这套评测是不是只写了几个脚本”。

---

## 16. Robustness Case 是怎么从真实 Bug 长出来的？

开发记录里有一组很典型的深水 Case：

```text
CASE_ROBUST_GHOST_INHERITANCE_BUDGET
→ 多次跨类目/区域/预算切换后，检查旧预算是否泄漏

CASE_ROBUST_COMPOUND_ORDINAL_CRITERIA
→ 同一 Turn 同时“第一家太贵” + “第二家有插座吗”
→ 检查 mutation 与 reference 是否互相破坏

CASE_ROBUST_LOCATION_REFUSAL_ESCAPE
→ CLARIFYING 后用户自然语言拒绝定位并要求全城
→ 检查能否正确逃离澄清态
```

它们第一次进入 robustness 时并没有全部通过，Run82 的 9 条里 Route 7/9、Final Status 5/9。这个数据反而有价值：Dataset 是先作为回归锚点记录真实缺陷，再推动生产 invariant 修复，而不是先把代码写到绿色才补“漂亮测试”。

### 面试官问：为什么把失败 Case 留在数据集里？

因为评测集的职责之一就是持久化系统已知边界。删除红 Case 会让后续回归失去锚点，也容易制造虚假的高通过率。

---

## 17. 面试官如果问最终数字，应该怎么回答

不要只报 Complete。当前归档阶段应该先说明：

```text
Complete = 多个 trajectory contract 的 AND
```

再按子指标讲 Route / Tool / Final / Locality，并说明 Holdout 是防 case chasing 的阶段验证，不是“证明系统已经泛化”。

如果被继续追问具体 Run，可以引用当前归档资料中的 Run144 / Run145 / Run146；但面试重点应该放在两件事：

1. 修改后 Robustness 是否出现系统性改善或退化；
2. Holdout 是否保持不退化。

不要把低 Complete 隐藏，也不要把它解释成“系统准确率只有这么多”。

---

## 18. 这条线真正应该背熟的四个问题

```text
1. 为什么最终回答正确还不够？
2. Ground Truth 怎么避免绑定模型偶然输出？
3. 失败以后如何定位到具体责任层和具体 Turn？
4. 怎么防止为了 Dataset 一直堆局部规则？
```

如果这四个问题能讲清，JSONL、Diagnostics、Run 号、具体断言 operator 都只是追问细节，不需要一开始死记。
