# AI 消费决策 Agent——模型选择、工程方法论与评测

> 这份资料是《AI消费决策Agent——面试主线总纲》的补充，专门解决三个容易被问深的问题：**模型为什么这么选、从踩坑里提炼出了什么通用方法论、评测到底怎么设计和解释。**
>
> 写法仍然按问题驱动：先解释为什么会遇到这个问题，再讲实验/演进，最后给可直接说的面试回答。

**Q1：模型到底怎么选？为什么不是“哪个模型能力榜更高就用哪个”？**

一开始很容易按模型排行榜、参数量或者网上口碑选模型，但这对 Agent 项目其实不够可靠。因为同一个模型在不同任务上的优势可能完全不同。

例如项目里有两类模型任务：

```text
任务A：结构化约束提取
→ 要稳定输出 JSON、地点/预算/菜系不能乱

任务B：最终答案叙事 / 润色
→ 要把已有结构化事实组织成人话
→ 不能添加不存在的商户事实
→ 不能把条件说错
→ 还要控制延迟和 Token
```

所以当时没有直接说“DeepSeek 强，所以都用 DeepSeek”或者“千问快，所以都用千问”，而是用同一批样本、同一个 Prompt 做任务级 A/B。

当时比较的是：

```text
deepseek-v4-flash
vs
qwen-flash
```

在“最终叙事生成”任务里，除了能不能生成，还专门检查了内容安全约束，也就是生成结果不能把已有事实说错、不能新增没有证据支撑的事实。

结果是：

| 指标 | deepseek-v4-flash | qwen-flash |
|---|---:|---:|
| 内容安全通过率 | 4/8 | **7/8** |
| 延迟 P50 | 6776ms | **5213ms** |
| 延迟 P95 | 9807ms | **5304ms** |
| Token 总量 | 1532 | **1016** |
| 调用失败 | 0/8 | 0/8 |

也就是说，在这一小组“强约束叙事”样本上，qwen-flash 不只是更快，反而更容易遵守“只基于已有事实生成”的约束。

后面又做了答案润色对比。两边都能保留原始事实，测试里都没有出现新增幻觉；但 DeepSeek 在一条较长输入上出现了截断，而 qwen-flash 能完整输出，延迟也更低。

这次实验不能得出：

```text
qwen 永远比 DeepSeek 好
```

只能得出：

```text
在这个项目、这类 Prompt、这批任务上，
qwen-flash 更适合强约束叙事/润色。
```

因此最终模型选择不是“一个模型包打天下”，而是按任务拆：qwen 更适合改写、路由、叙事/润色等对延迟和约束遵循敏感的任务；DeepSeek 继续用于约束提取等已经验证过的任务。

这里还有一个更重要的判断标准：

```text
模型错了以后，代码能不能接住？
```

预算、距离、菜系、行政身份这些硬事实，即使模型输出错，也不能直接成为最终业务事实；后面还有 schema、Resolver、hard filter、Reducer 等代码约束。因此某个任务如果有强 deterministic guard，就可以更积极地使用轻量模型降低延迟和成本。

> **面试回答：**
> 我不会按排行榜直接选模型，而是按真实任务做同 Prompt、同样本 A/B。当时比较过 deepseek-v4-flash 和 qwen-flash 做最终叙事生成。在 8 条强约束样本上，qwen 的内容安全通过率是 7/8，DeepSeek 是 4/8；P50 从 6776ms 降到 5213ms，P95 从 9807ms 降到 5304ms，Token 也少了约 34%。润色任务里两边事实一致性都保住、都没有幻觉，但 DeepSeek 有一条长输入截断。我的结论不是“千问一定更强”，而是模型选择必须任务级验证，同时看质量、延迟和 Token；而且还要看模型犯错后有没有 deterministic guard 能接住。

**可能追问：8 条样本能说明什么？**

只能作为任务级工程证据，不能做统计学意义上的“模型优劣结论”。所以面试不要说“实验证明 qwen 全面优于 DeepSeek”，应该说“在当前任务和固定样本上 qwen 更合适，因此作为工程选型依据”。

---

**Q2：项目踩了这么多坑，除了一个个 Bug，你最后有没有提炼出通用的方法论？**

有，而且这部分比记住具体类名更重要。

最值得记的是下面几条。

### 方法一：对于会改变状态的动作，最终要验证“动作后的状态”，不能只看“调用了什么动作”

这个方法和之前 MiniClaude 处理死循环时的经验是同一类问题。

MiniClaude 一开始做命令判重：

```text
连续执行相同命令
→ 怀疑死循环
```

但后来发现，仅比较命令不够。两个命令字符串不同，也可能对工作区没有任何新影响；反过来，同一个命令重复执行也可能确实让工作区继续发生变化。

所以判断标准逐渐从：

```text
Action Identity
```

变成：

```text
Post-State / Workspace Mutation
```

也就是：

> **状态改变类动作，最终应该根据执行后的 canonical state 判断是否真的发生了有效变化。**

DP-Plus 里其实反复出现同一个模式。

例子 1：无结果放宽。

```text
系统执行了 BROADEN_FOOD_SCOPE
```

不能因为“命令执行过”就认为恢复成功，还要看：

```text
Working Memory 里的 keyword/cuisine 是否真的被清掉
```

当时真实 Bug 就是：DecisionSession 已经放宽，但 Working Memory 仍然保留“兰州拉面”。动作看起来成功，canonical state 实际没变。

例子 2：Location。

```text
高德成功返回 POI candidate
```

也不能直接等于：

```text
地点解析成功
```

必须继续验证：

```text
是否确认成 canonical POI
是否真正写成 searchLocation
```

所以才有：

```text
Retrieval ≠ Resolution
```

例子 3：慢 Tool。

```text
Tool 调用成功
```

仍然不代表结果现在有效。还要看它返回时 Working Memory version 有没有变化。如果系统已经从 v20 变到 v21，那么基于 v20 得到的 Tool Result 就应该被判 stale。

可以把这条方法论总结成：

```text
不要只验证“做了什么”
要验证“做完后系统处于什么状态”
```

> **面试回答：**
> 我后来形成一个比较重要的方法：对于会改变状态或依赖状态的 Agent 动作，不能只验证 action/tool 是否被调用，而要验证执行后的 canonical post-state。比如 BROADEN_FOOD_SCOPE 不能只看 command 被执行，还要断言 WM 里的 keyword/cuisine 真被清掉；地图 API 返回 candidate 也不代表地点确认成功，还要看 canonical searchLocation；慢 Tool 即使成功返回，也要校验当前 WM version。这个思路和我另一个 Coding Agent 项目里从“命令判重”演进到“工作区状态判重”本质一样：最终正确性应该落在状态变化上。

### 方法二：连续补规则还解决不了时，先怀疑“表示方式错了”，不要继续加 if

很多大改造都不是因为某个 if 写错了，而是系统表达问题的方式不对。

```text
A→B→A 状态串扰
第一反应：继续补字段 merge/clear
真正问题：用户同时存在多套需求
→ Flat WM 改成 Task
```

```text
“最开始第二家”找不到
第一反应：shownShopIds 再加点字段
真正问题：历史推荐丢失 batch boundary
→ RecommendationBatch
```

```text
“师大”搜到餐馆/民宿
第一反应：按 UNIVERSITY 类型硬过滤
真正问题：Search 只是 Retrieval，不是 Entity Resolution
→ Candidate Retrieval 与 Canonical Resolution 分开
```

```text
“这个日料咋样”修改 cuisine
第一反应：给日料加特殊判断
真正问题：Mention 和 Mutation 是两个维度
→ Turn Semantics
```

所以当同一类 Bug 需要越来越多特殊分支时，优先问：

> **是不是 representation 少了一个维度？是不是两个本来不同的概念被压成了一个字段/状态？**

> **面试回答：**
> 我不会把所有线上 Bad Case 都看成“少一个 if”。如果同一类问题反复需要特判，我会先检查 representation。这个项目里 Flat WM→Task、candidatePool→RecommendationBatch、Mention→Mutation 分离、Retrieval→Resolution 分离，本质都是发现原来的数据模型把两个不同概念压在了一起。我的经验是：规则开始指数增长时，先怀疑表示方式和 authority，而不是继续补词表。

### 方法三：先找“谁拥有最终解释权”，再写代码

很多 Bug 本质上是两个模块都以为自己能决定同一件事。

例如：

```text
LLM 说 targetDistrict=连江县
Resolver 说 NOT_FOUND
```

如果旧字段仍然留下，就出现行政 Authority Leak。

再例如：

```text
TurnUnderstanding 说本轮不修改条件
旧 route 逻辑却根据 Extractor delta 判断有 mutation
```

这就是两个 Mutation Authority。

所以设计新功能时先问：

```text
当前预算谁说了算？        → Working Memory
用户有没有改条件谁说了算？ → Turn Semantics
“第二家”是谁谁说了算？    → Reference Resolver
行政身份谁说了算？         → Administrative Resolver/Provider
历史推荐原因谁说了算？      → 当时 DecisionSession
```

> **面试回答：**
> 这个项目后期我经常先画 authority map：同一个事实只能有一个最终 writer/reader authority。很多 Bug 其实不是模型错，而是旧逻辑旁路还保留写权，比如 Resolver 已经否定一个行政实体，但 LLM 提取字段仍留在 WM；或者新 Turn Semantics 已经判断不 mutation，旧 route 又根据 delta 再判断一次。先明确谁说了算，比继续调 Prompt 更重要。

### 方法四：修复必须能被“相邻反例”攻击，不能只验证原 Case

例如：

```text
问题：师大候选里有餐馆
方案：只允许 UNIVERSITY
```

原 Case 看起来好了。

但马上用相邻反例：

```text
福建师范大学附属小学
```

就能证明这个方案错误。

这类测试不是为了抬杠，而是在问：

```text
这个方案解决的是原则问题
还是只记住了当前 Case？
```

> **面试回答：**
> 我现在修一个 Bad Case 不会只重放原输入，而会主动找一个最接近的反例。如果方案只对原 Case 成立，就不应该进入主架构。Location 的 UNIVERSITY hard filter 就是这样被“福建师范大学附属小学”击穿的，最后我选择回撤，而不是继续补小学、中学 taxonomy。

### 方法五：评测失败先定位 failure layer，再决定要不要改生产代码

一次 Case 失败可能来自：

```text
理解错
状态写错
Reference 错
Location 错
Tool 选错
检索没召回
Rerank 错
答案生成错
Ground Truth 本身和当前语义 contract 不一致
```

如果只看到：

```text
Complete = false
```

就直接改 Prompt，很容易修错层。

所以项目后期会拆 Route、Tool、Final、Locality、Working Memory、unseen recommendation 等指标。

> **面试回答：**
> 我会先做 failure-layer decomposition。Agent 是链式系统，一个最终失败不等于模型理解失败。Route、Tool、State、Retrieval、Generation 必须拆开看，只有定位到真正层级后才决定改 Prompt、代码、检索还是评测 contract。这样能避免“哪里红就改哪里”的盲修。

---

**Q3：评测为什么不能只看一个“总准确率”？应该怎么分层？**

因为 Agent 和普通分类模型不一样，它是一条执行链。

一次对话最后回复看起来没问题，中间仍然可能错：

```text
Route 错了，但模型碰巧生成正确回答
Tool 调错了，但 LLM 用常识补出了结果
Working Memory 已经被污染，只是这一轮暂时没暴露
Reference 绑错了店，但两个店信息相似
```

所以评测要分层。

项目里可以理解成五层：

```text
第一层：Unit / targeted tests
→ 验证局部不变量

第二层：HTTP directed smoke
→ 验证真实 Controller → Orchestration → DB/Provider wiring

第三层：Conversation Evaluation
→ 验证多轮 Route / Tool / State / Final Contract

第四层：Holdout
→ 检查是否只拟合固定评测集

第五层：真实自然语言 smoke
→ 补 Dataset 很难覆盖的简称、省略、口语、前端交互
```

这五层不是互相替代。

单测全绿不能证明真实 API wiring 没问题；Conversation Dataset 很强，也不一定覆盖“农大”“这个日本料理”“搞什么”这种真实表达；人工 smoke 又不能替代可重复的离线回归。

> **面试回答：**
> Agent Evaluation 我会分层做，而不是追求一个总分。单测保证局部 invariant，HTTP smoke 验证真实 wiring，多轮 Dataset 检查 Route/Tool/State/Final，Holdout 防止对固定数据集过拟合，最后再用真实口语做人工 smoke。每一层解决的问题不同，组合起来才比较接近真实可靠性。

---

**Q4：检索评测里的 Recall@K、MRR、证据覆盖率分别是在看什么？**

假设一个 Query 的正确商户是 A。

### Recall@K

看前 K 个结果里有没有把正确商户找回来。

```text
Top3 = B / A / C
→ Recall@3 命中
```

它主要回答：

```text
召回阶段有没有把正确答案放进候选集
```

### MRR

MRR 更关心正确答案排得靠不靠前。

例如：

```text
A 排第1 → RR=1
A 排第2 → RR=1/2
A 排第5 → RR=1/5
```

多个 Query 再取平均得到 MRR。

所以两个系统 Recall@K 一样时，MRR 还能区分谁把正确结果排得更前。

### 证据覆盖率

这个项目不仅要推荐商户，还希望推荐理由有事实支撑。

可以简单理解成：

```text
最终 Top-K 商户中
有至少一条有效 evidence 的商户数
/
最终 Top-K 商户总数
```

它回答的不是“排序对不对”，而是：

```text
推荐结果有多少真正能解释
```

项目早期 vector-v3 的真实基线里，15 条 `seed-v2` 用例得到：

```text
Recall@K = 0.8333
MRR = 0.5833
Evidence Coverage = 1.0
约束提取 = 15/15
硬约束违规 = 0
事实一致 = 15/15
模型调用 = 29/29 成功
平均总延迟约 4349ms
```

这组数据最重要的不是“0.8333 高不高”，而是它同时看了：

```text
召回质量
排序位置
证据完整性
硬约束安全
事实一致性
系统性能
```

> **面试回答：**
> Recall@K 看正确候选有没有被召回来，MRR 看正确候选排得有多靠前，证据覆盖率看最终推荐有多少真正有 Evidence 支撑。我不会只优化一个指标，因为纯提高 Recall 可能引入大量噪声，纯提高排序又可能牺牲硬约束。我的检索评测会同时看 Recall/MRR、Evidence Coverage、硬约束违规、事实一致性和延迟。

---

**Q5：为什么实验必须尽量和生产链路等价？**

这是检索实验里很容易忽略的问题。

比如生产里 Milvus 是：

```text
在 hardMatched 白名单内做 pre-filter / ANN
```

如果离线实验却变成：

```text
先全库向量召回
→ 再在 Python 里 post-filter
```

即使最后指标很好，也不能说明生产效果会一样。

同样，生产里如果 Review 是 document-level 召回再按 shop 聚合，实验就不能只保存最终 shop score，否则后面根本没法检查：

```text
是哪个 Review 命中的？
MAX 聚合是不是被单条噪声抬高？
阈值是在 document 前还是 shop 后？
```

因此实验阶段应该尽量保留原始 document-level 结果，再按照和生产一致的聚合、阈值、pre-filter 方式计算指标。

> **面试回答：**
> 我做检索实验时比较强调 production equivalence。比如 Milvus filter 在生产是 ANN 前的 pre-filter，离线就不能换成全库召回后 post-filter；生产按 document 召回再聚合到 shop，实验也要保留 document-level raw result。否则实验指标再漂亮，测的也不是实际线上链路。

---

**Q6：A/B 或消融实验到底怎么判断“值得上线”？是不是指标涨一点就算有效？**

不是。

项目做过 content-only 和 content+tags 的检索实验。

结果里：

```text
Recall@3：0.7932 → 0.7932
Top1：    0.9655 → 0.9655
NDCG@3：  0.9828 → 0.9873
```

如果只看 NDCG，会说：

```text
涨了，tags 有用
```

但结合 Recall@3 和 Top1 都没变化，这个提升非常小，而且还要承担：

```text
更多 metadata 构造
更多索引治理
更多同步复杂度
```

所以最后不能简单因为一个指标小涨就扩大生产复杂度。

这也是为什么实验要围绕一个明确 Claim：

```text
我要证明 tags 提升召回？
还是改善排序？
还是改善 evidence 命中？
```

不同 Claim 对应不同指标。

> **面试回答：**
> A/B 不是“有一个指标上涨就上线”。我做过 content-only vs content+tags，Recall@3 和 Top1 都没变化，只有 NDCG@3 从 0.9828 小幅到 0.9873。这个结果不足以证明 tags 带来的复杂度值得上线。我的习惯是先明确实验 Claim，再选对应指标，同时把工程复杂度、延迟和维护成本算进去。

---

**Q7：为什么 Route、Tool、硬事实这些地方不优先用 LLM-as-a-Judge？**

因为这些东西本来就有确定答案。

例如：

```text
期望 Route = SHOP_EVIDENCE
实际 Route = GENERAL_CHAT
```

没必要再问另一个 LLM：

```text
你觉得它算不算对？
```

Tool Name、selectedOption、Working Memory 字段、硬约束违规、shopId、版本号，都可以 deterministic compare。

LLM-as-a-Judge 更适合补充评估：

```text
表达是否自然
解释是否清楚
语气是否符合要求
两个答案哪一个整体更有帮助
```

而且即便使用 Judge，也应该保留固定 rubric、版本和抽样人工校验。

> **面试回答：**
> 我会优先用 deterministic evaluator 评 Route、Slot、Tool、WM、硬事实，因为这些都有明确 Ground Truth。LLM-as-a-Judge 更适合主观生成质量，不应该替代本来能精确比较的指标。否则评测本身又引入一个概率模型，失败时更难定位。

---

**Q8：最终 Conversation Evaluation 为什么要同时看 Route、Tool、Final、Locality、Complete？**

因为一个 Case 可能出现：

```text
Route 对
Tool 对
Final 对
但历史 unseenRecommendations 不满足
```

这种情况下：

```text
Complete = false
```

所以 Complete 是多个 Contract 的 AND，不是普通“准确率”。

最终归档：

```text
Unit Tests
402 tests / 0 failures / 0 errors / 3 skipped
```

Robustness Run144：

```text
48 cases
Complete 21/48
Route 44/48
Tool 47/48
Final 37/48
Locality 48/48
Model Failures 0
```

Conversation-v1 Run145：

```text
40 cases
Complete 28/40
Route 37/40
Tool 32/40
Final 40/40
Locality 40/40
Model Failures 0
```

Holdout Run146：

```text
16 cases
Complete 7/16
Route 13/16
Tool 14/16
Final 12/16
Locality 16/16
Model Failures 0
```

相对上一版：

```text
Robustness：Complete +1 / Route +1 / Final +1
Conversation-v1：只确认一条 ambiguous probe 新回归
Holdout：功能指标完全一致
```

这也是最后决定停止开发的重要依据：没有看到系统性回归，Holdout 没下降，而继续逐 Case 加规则已经开始产生过拟合风险。

> **面试回答：**
> Conversation Evaluation 我不会只报 Complete，因为它是多个 Contract 的 AND。最终我会拆 Route、Tool、Final、Locality、Working Memory 等指标看失败层级。归档版本 402 个单测全绿，Robustness 相比上一版有小幅提升，Holdout 功能指标完全持平，Conversation-v1 只有一条 ambiguous probe 新回归，所以我判断没有系统性退化，选择封档而不是继续追 100% Dataset。

---

**Q9：你最终形成的一套“Agent 开发—评测闭环”是什么？**

可以把整个项目最后的方法压成下面这条链：

```text
1. 真实 Bad Case
   ↓
2. 先定位 failure layer
   ↓
3. 判断是规则 Bug，还是 representation / authority 问题
   ↓
4. 提炼一个 invariant，而不是记住当前输入
   ↓
5. 用相邻反例攻击方案
   ↓
6. targeted unit test 验局部不变量
   ↓
7. HTTP smoke 验真实 wiring
   ↓
8. 相关多轮 Dataset 验回归
   ↓
9. Holdout 检查是否过拟合
   ↓
10. 看 Post-State，而不是只看 Action 是否执行
   ↓
11. 如果修复开始变成 case chasing，就停止继续堆规则
```

这套闭环里最关键的不是某个框架，而是几个判断：

```text
Action 是否真的改变了正确状态？
谁拥有最终 authority？
是不是 representation 本身少了一层？
实验是否和生产等价？
指标是否真的支持你要证明的 Claim？
```

> **面试回答：**
> 我后来把 Agent 开发方式从“发现 Case 就修 Case”变成了 invariant-driven。先用真实 Bad Case 定位失败层，再判断是不是 representation 或 authority 问题；方案出来后主动找相邻反例；局部先用 deterministic test 验不变量，再做真实 HTTP smoke、多轮 regression 和 holdout；对于状态改变类动作，最终看 canonical post-state，而不是只看 command/tool 被调用。最后如果修复开始依赖越来越多特判，我会把它视为 case chasing 信号，而不是继续刷评测分数。

---

## 面试前最后速记

如果这份资料只背 8 句话，背下面这些：

```text
1. 模型选型不是看排行榜，而是同 Prompt、同样本做任务级 A/B，联合看质量、延迟、Token。

2. 轻模型能不能用，还要看“它错了以后 deterministic guard 能不能接住”。

3. 对状态改变类动作，不只看 Action 是否执行，要看 canonical Post-State 是否真的正确。

4. 特判越来越多时，优先怀疑 representation 或 authority，而不是继续加 if。

5. 一个事实只能有一个最终 authority；旧旁路必须失去写权。

6. 修复原 Case 后要用相邻反例攻击方案，防止 case-specific patch。

7. Evaluation 先拆 failure layer，再决定改 Prompt、状态、Tool、检索还是 Ground Truth Contract。

8. 实验必须尽量 production-equivalent；指标要服务于明确 Claim，不是“有一个数字涨了就算有效”。
```
