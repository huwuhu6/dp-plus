# 多轮状态与 Working Memory——简历驱动追问链

## 0. 如果简历要写这一点，怎么写

建议简历 bullet：

> 设计多轮消费决策 Working Memory，将 Chat History 与当前业务状态解耦，围绕约束生命周期、任务作用域、候选批次与历史引用建立结构化状态；支持条件增量修改/显式清除、A→B→A 任务切换，并通过版本化状态、OCC 与运行时版本校验避免旧状态和慢工具结果污染当前会话。

这句话主动暴露的关键词只有：`Working Memory`、约束生命周期、Task Scope、历史引用、OCC。不要在简历上继续把 TurnCommandSet、RecommendationBatch、Location Contract、Provenance、CuisineCanonicalizer 等内部名词全部倒出来，这些应该作为被追问后的深挖证据。

---

# 一、先看这一条线到底是怎么长出来的：开发演进速览

这条线不是一开始就设计好了一套 Working Memory。早期甚至没有明确的 Working Memory 抽象，后续几乎整个开发周期都在围绕三个问题不断返工：

```text
1. 当前到底什么状态是真的？
2. 用户这一轮到底是在 SET / CLEAR / REMOVE / SWITCH，还是只是提到一个词？
3. 一份状态产生以后，哪些派生结果还有效，哪些历史事实必须保留？
```

整条演进可以先记成两条互相缠绕的轴。

### 轴 A：State Scope / Consistency

```text
没有明确 Working Memory
→ 状态散落
→ candidatePool / shown history 分离
→ RecommendationBatch
→ Flat Working Memory
→ A→B→A Ghost Inheritance
→ Task-scoped Working Memory V2
→ 同一 Turn 双 Snapshot Bug
→ request-scoped authoritative snapshot
→ version / OCC
→ baseWorkingMemoryVersion / stale runtime guard
```

### 轴 B：Slot Contract / Mutation Semantics

```text
一堆专用字段不断增加
→ occasion / quiet / avoidQueue / softPreferences 职责混乱
→ 17 字段盘点，开放属性收敛到 preferences
→ cuisine 两侧 canonicalization 不一致
→ CuisineCanonicalizer
→ cuisine / keyword schema 重叠
→ keyword 只做 semantic query，造成“最像但不符合”
→ keyword/cuisine 职责重新划分并做确定性约束
→ 未提及 vs 显式清除
→ clearedFields / removedPreferences
→ “这个日料怎么样”暴露 Mention ≠ Mutation
→ Turn Understanding 成为 mutation permission authority
→ constraintSources / provenance
```

Reference、Location、Zero-result FSM 则是这两条轴继续往真实业务里延伸后的结果。

---

# 二、第一阶段：最开始没有 Working Memory，状态是散的

早期系统更接近：

```text
Chat History
+ ChatProcessingContext
+ DecisionSession
+ candidate list
+ focused shop
+ agent_context_json / session 字段
```

简单的：

```text
福州火锅100
→ 预算改80
```

还能靠 History + 当轮提取跑起来。

但“换一批”“之前那家”“切另一套需求再回来”之后，状态开始互相覆盖。这里最先得到的原则是：

> Chat History 是语言证据，不应该承担当前业务真值。

Working Memory 的出现，本质不是为了“记得更多”，而是为了明确一个 canonical state authority。

---

# 三、候选状态先暴露问题：current candidate ≠ shown history ≠ batch history

第一批：

```text
A / B / C
```

换一批：

```text
D / E / F
```

如果只保留一个 candidatePool，第一批消失；如果只保留：

```text
shownShopIds=[A,B,C,D,E,F]
```

又回答不了：

```text
“最开始第二家”
```

所以逐步拆出：

```text
candidatePool
= 当前 retrieval domain 下仍有效的候选

shownShopIds
= 历史展示过哪些店

RecommendationBatch
= 每一批的边界、顺序以及当时 decisionSessionId
```

这一步很关键：

> Current Projection 可以失效，但 Historical Fact 不能被抹掉。

后面 Working Memory 很多设计都沿用这条原则。

---

# 四、Reference 也经历过“信息散落 → 单一结构化语义”

早期“第一家/第二家/这个/刚才那家”的信息会散在：

```text
candidate ordinal
focusedShop
rewritten query
历史候选
各模块自己的字符串判断
```

这样容易出现两个问题：

1. Context Rewrite 认为“第二家”指 B；
2. Tool Routing 又重新按当前 candidatePool 猜一次，可能得到 E。

后来逐步收敛成：

```text
ReferenceIntent
→ BatchAwareReferenceResolver
→ ResolvedShopReference
```

ReferenceIntent 负责描述：

```text
ordinal / latest / earliest / focused / descriptive qualifier ...
```

Resolver 才负责把它绑定到具体 RecommendationBatch 与 shop。

评测也从“写死某个店名”改成 `candidateOrdinal`：从上一轮实际 Recommendation Snapshot 取第 2 个，再验证这一轮解析是否绑定到了它。

一句话：

> 指代语义也需要 canonical representation，不能每层各猜一次。

---

# 五、Flat Working Memory 出现后，A→B→A 仍然发生 Ghost Inheritance

最初 Flat WM 类似：

```text
activeCriteria
searchLocation
candidatePool
focusedShop
```

例如：

```text
A：福州 / 日料 / 100
B：杭州 / 西餐 / 300
用户：回到刚才的日料
```

真实 robustness 曾出现回到 A 后预算仍是 B 的 300，地点/品类也有同类污染。

这时候继续加：

```text
if 换城市 clear budget
if 换菜系 clear location
...
```

只会制造更多脆弱规则。

根因是 representation：用户会在一个会话里同时维护多套独立消费方案，Flat State 无法表达它们。

---

# 六、Working Memory V2：从 Flat State 到 Task Scope

于是 V2 变成：

```text
ConversationWorkingMemory
├─ activeTaskId
└─ tasks[]
    ├─ criteria
    ├─ constraintSources
    ├─ searchLocation
    └─ recommendationBatches[]
```

A 和 B 不再互相覆盖：

```text
Task A = 福州 / 日料 / 100
Task B = 杭州 / 西餐 / 300
```

“回到刚才的日料”做的是 ACTIVATE A，而不是从 B 上重新拼 A。

为什么不直接恢复历史 WM version？

```text
Version
= 时间维，过去某一刻系统长什么样

Task
= 业务身份维，当前会话里有哪些独立方案
```

两者不能替代。

---

# 七、Task V2 第一版仍然失败：同一 Turn 出现两个 Working Memory 世界

这是非常值得讲的真实事故。

Task transition 的局部测试能过，但完整 Conversation Evaluation 里：

```text
taskCount 始终为1
activeTaskId 没真正保持切换
```

最后发现：

```text
前半段 transitionTask()
→ 改了 context 里的 Working Memory

后半段 reduceCriteria()
→ 又从 DB reload 旧 snapshot
→ 把新 Task 覆盖掉
```

所以形成不变量：

> 一个 Turn 只能有一份 authoritative Working Memory snapshot。

```text
Bootstrap load once
→ transition
→ merge
→ reduce
→ persist
```

全部围绕 request-scoped snapshot 工作。

这能证明：数据模型正确还不够，Pipeline 的 state view 也必须一致。

---

# 八、槽位 Schema 曾经越来越“专用”，最后发现字段爆炸本身就是问题

开发记录做过一次 17 字段盘点，其中几个典型问题：

```text
occasion
→ 为“约会”等 Case 单独建字段
→ 但场景本身是开放集：约会/商务/聚餐/宵夜/一个人吃...

quiet / avoidQueue
→ 本质是属性偏好
→ 却各自变成独立 boolean

softPreferences
→ 同时混用户偏好、系统推导、解释标签
```

结果是每出现一个新场景就倾向增加字段：

```text
quiet
avoidQueue
occasion
lightTaste
...
```

长期一定爆炸。

最后方向是：

```text
结构化硬约束
→ budget / radius / location / cuisine ...

开放属性/场景
→ preferences[]
```

例如：

```text
“和女朋友吃饭”
→ preference:约会 = DERIVED

“想找安静一点”
→ preference:安静 = USER_EXPLICIT
```

所以这一阶段的教训是：

> Slot Schema 不是越细越好；有限、确定、会参与业务约束的维度适合独立字段，开放集语义更适合 canonical tags + provenance。

---

# 九、同一个 cuisine，在用户侧和商户侧 canonicalization 不一致

又有一类很典型的问题：

```text
用户：日本料理
商户画像：日料,寿司
```

如果用户侧靠 contains / alias mapping，商户侧靠逗号分割精确匹配，两侧 normalization 不一致，就会出现：

```text
语义上同一种菜系
→ 结构化硬过滤却匹配不上
```

所以后面抽出统一的 `CuisineCanonicalizer`，让：

```text
日本料理 → 日料
韩国料理 → 韩餐
...
```

在 extraction、merge、hard filter 等链路里使用同一 canonical identity。

一句话：

> Canonicalization 也必须只有一个 authority；用户侧和数据侧不能各维护一套近似规则。

---

# 十、cuisine / keyword 冲突：这是“提取错一点，检索错一大片”的典型事故

这是非常值得背的真实 Case。

例如：

```text
用户：想吃沙县小吃
```

早期 schema 边界不清：

```text
某次模型：cuisine=沙县小吃
另一次模型：keyword=沙县小吃
```

更严重的是，当时 `keyword` 主要进入 semantic retrieval query，并不参与确定性 hard filter。

于是如果附近数据里根本没有沙县：

```text
hard candidate universe
= 附近全部餐饮店

Milvus
= 从这些错误 universe 里挑“语义最像”的

结果
= semanticScore 很低的东北菜等店仍被当作 SUFFICIENT 推荐
```

这说明：

> Semantic Retrieval 只能在“合法候选宇宙”里排序，不能承担“这个实体/品类到底存在不存在”的确定性约束职责。

后续做了两层收口：

```text
1. schema 明确：
   cuisine = 菜系类别
   keyword = 用户点名的具体实体/店名/招牌菜

2. 对可 canonicalize 成 cuisine 的 keyword 做迁移
   → 进入 cuisine
   → 参与 deterministic hard filtering
```

这条 Case 其实把 Constraint Extraction、Slot Schema、Hard Filter、Semantic Retrieval 四层串在了一起，非常适合面试。

---

# 十一、未提及 ≠ 显式清除：null 不能同时表达两种操作

上一轮：

```text
budget=100
preferences=[安静]
```

这一轮没提预算：

```text
absent
→ inherit 100
```

用户说：

```text
“不限预算”
→ clear budget
```

如果两种情况都用 `null`，Merger 无法知道用户有没有发出 clear command。

因此增加：

```text
clearedFields[]
removedPreferences[]
```

例如：

```text
“不限预算”
→ clearedFields += budget

“不要安静了”
→ removedPreferences += 安静
```

一句话：

> Value 和 Mutation Operation 分开；absent 是“没有操作”，clear/remove 是明确操作。

---

# 十二、Mention ≠ Mutation：Extractor 抽到字段，不代表有权写 Working Memory

真实 Bad Case：

```text
用户：这个日料怎么样？
Extractor：cuisine=日料
```

如果：

```text
Delta 非空 → APPLY_DELTA
```

事实追问就会偷偷修改搜索条件。

所以最后把三种 authority 拆开：

```text
Turn Understanding
→ 能不能改

Constraint Extractor
→ 如果能改，具体改什么

Merger / Reducer
→ 怎么写 canonical state
```

这也是为什么不能只靠 Prompt 说“别乱改”。Prompt 可以降低概率，不能承担 durable state mutation permission。

---

# 十三、级联失效：改了条件，不只是改一个字段

例如：

```text
原来川菜
candidatePool=A/B/C
focusedShop=A

用户：换粤菜
```

如果只把：

```text
cuisine=粤菜
```

写入 WM，却保留 A/B/C 和 focused A，那么后续：

```text
“第二家怎么样？”
```

仍可能去查旧川菜候选。

所以 search-domain 关键字段变化必须：

```text
invalidate candidatePool
clear focusedShop
```

但 RecommendationBatch 不能删，因为 A/B/C 仍然是用户真实看过的历史结果。

再次回到那条不变量：

> Current Projection 可以失效，Historical Fact 不能删除。

---

# 十四、Location 继续把 Working Memory 的边界逼深

几个连续 Case：

```text
GPS 在福州
用户：北京有啥好吃的
```

设备位置 ≠ 搜索目的地。

```text
重庆 / EXPLICIT_TARGET
用户：那我附近呢
```

最新用户意图要求 scope switch，不能保留旧重庆。

```text
福建有什么推荐
```

如果只有 targetCity，福建会被错误投影成 city='福建'，说明行政 level 也必须进入 canonical state。

```text
师大附近
```

POI search 返回第一条 ≠ 已确认 identity。

于是逐步得到：

```text
Value ≠ Intent ≠ Provenance ≠ Projection
Location Mention ≠ Verified Identity
```

并将：

```text
locationIntent
province/city/district/area
searchLocation
deviceLocation
```

职责逐步拆开。

---

# 十五、Zero Result 也属于状态语义，不是“搜不到就扩大范围”

两类 0 结果必须区分：

```text
有预算/菜系/半径等真实可松弛条件
→ WAITING_RELAXATION

只指定了合法城市，但数据库根本没覆盖
→ ZERO_RESULT_NO_DATA
```

如果用户没给任何可放宽约束，却展示“放宽条件”，状态机就在撒谎。

而 hard constraint 的放宽必须来自用户确认，不能系统偷偷扩大范围。

---

# 十六、同一 Turn 一致后，还要处理跨请求 Lost Update：OCC

```text
A 读取 v20
B 读取 v20
B 先提交 v21
A 再提交
```

A 不能覆盖 B。

Working Memory append 使用 expectedVersion / version 做 OCC，版本冲突拒绝旧写。

这里不要吹成“系统完全不串行”。正确表述：

```text
request-scoped snapshot
→ 单请求一致性

session-level serialization（若命中）
→ 降低同会话竞争

OCC
→ durable state 最后一道 Lost Update 防线
```

---

# 十七、OCC 还不够：慢 Tool 的旧结果也会污染新状态

```text
Tool 基于 WM v20 执行8秒
期间用户把 WM 推到 v21
Tool 返回
```

即使最终 append 会版本冲突，Tool Result 仍然是旧世界算出来的。

所以 runtime context 保存：

```text
baseWorkingMemoryVersion
```

返回时如果：

```text
base=20
latest=21
```

则记为 stale runtime result，禁止回写：

```text
candidatePool
focusedShop
criteria
shown state
```

记忆：

```text
same-turn snapshot
→ 防一个请求两个世界

OCC
→ 防两个请求覆盖

stale runtime guard
→ 防慢 Tool 用旧世界污染新状态
```

---

# 十八、最后连“当前值”也不够：还要记录 Provenance 与 Historical Decision Fact

用户会问：

```text
“我之前说过安静吗？”
“3公里是我定的吗？”
“为什么当时推荐第一家？”
```

只有 Value 无法回答。

所以拆成：

```text
Value
→ 当前生效什么

Provenance
→ USER_EXPLICIT / DERIVED / SYSTEM_DEFAULT

Historical Decision Fact
→ 当时为什么推荐
```

例如：

```text
“想找安静一点”
→ preference:安静 = USER_EXPLICIT

“适合聊天”
→ preference:安静 = DERIVED
```

历史推荐解释则：

```text
ResolvedShopReference
→ RecommendationBatch.decisionSessionId
→ historical DecisionSession.resultJson
→ matchedReasons / evidence
```

不拿今天的 criteria 重解释昨天的 recommendation，避免 Temporal Leakage。

---

# 十九、面试追问树：从简历这一句话最可能怎么一路问

```text
为什么不用 Chat History？
→ Working Memory 存什么？
→ Slot 怎么更新？
→ 没提 vs clear 怎么区分？
→ “这个日料怎么样”为什么不改 cuisine？
→ cuisine / keyword 怎么划分？
→ 为什么 keyword 不能只扔给向量检索？
→ 为什么 quiet / occasion 不全做独立字段？
→ 条件变化为什么要清 candidatePool / focusedShop？
→ Flat WM 为什么不够？
→ Task UPDATE / CREATE / ACTIVATE 怎么定？
→ “最开始第二家”怎么找？
→ 为什么需要 RecommendationBatch？
→ Task V2 为什么第一版还失败？
→ 同一 Turn snapshot 怎么保证？
→ 并发怎么防 Lost Update？
→ Tool 慢8秒回来怎么办？
→ GPS 与显式地点冲突怎么办？
→ 0结果为什么不能统一放宽？
→ “我说过安静吗”怎么回答？
```

这已经足够支撑一整轮深挖。

---

# 二十、最值得背熟的 8 个真实事故

如果没时间，优先把这 8 个讲顺：

1. **A→B→A Ghost Inheritance**：为什么 Flat WM 不够；
2. **Task V2 同 Turn reload 旧 snapshot**：为什么数据模型对了 Pipeline 仍会错；
3. **`occasion/quiet/avoidQueue` 字段不断增加**：为什么开放语义不能无限建专用 slot；
4. **“沙县小吃” cuisine/keyword 漂移**：为什么 Schema authority 必须明确；
5. **keyword 只做 semantic query**：为什么没有硬约束时向量会“硬选最像的错误结果”；
6. **“这个日料怎么样”污染 cuisine**：Mention ≠ Mutation；
7. **重庆 → “我附近”残留 EXPLICIT_TARGET**：Value / Intent / Provenance / Projection；
8. **Tool 基于 v20，返回时 WM 已 v21**：OCC 与 stale runtime guard 的区别。

---

# 二十一、1 分钟收口版本

> 这个 Working Memory 不是一开始设计出来的。最早状态散在 History、Session、候选列表和各类 Context 里，先遇到的是换一批后历史 ordinal 丢失，所以把 current candidate、shown history 和 RecommendationBatch 分开。后来 Flat Working Memory 在 A→B→A 场景出现 Ghost Inheritance，于是演进成 Task-scoped State；V2 第一版又因为同一个 Turn 后半段重新 load 旧 snapshot，把新 Task 覆盖掉，所以继续统一 request-scoped authoritative snapshot。另一条线是槽位契约：早期为了约会、安静、不排队不断加专用字段，后来盘点发现开放语义会导致 schema 爆炸，于是收敛为 hard structured constraints + preference tags；又处理了 cuisine canonicalization、cuisine/keyword 边界，以及 keyword 不参与 hard filter 导致向量从错误候选里硬选“最像”结果的问题。状态更新层再增加 clearedFields/removedPreferences 区分未提及和主动清除，用 Turn Understanding 解决 Mention ≠ Mutation。最后 Location 又逼着我们把 Value、Intent、Provenance、Projection 分开；并发层用 version/OCC 防 Lost Update，慢 Tool 再用 baseWorkingMemoryVersion 防 stale result 回写。整个演进的核心就是不断把“谁是真值、谁能改、改完什么失效、什么历史必须保留”从概率模型里收回成确定性状态契约。

---

# 二十二、当前不能吹什么

- Working Memory 当前是 versioned full snapshot，不是完整 Event Sourcing；
- Task Identity 是餐饮业务 heuristic，不是通用 Task Planner；
- Turn Semantics 是当前业务所需的轻量 command boundary，不是完整 Dialogue Act / DSL；
- preferences 的开放语义消费能力仍不是完整学习型 preference model；
- `keyword/cuisine` 已做职责收口，但不能包装成通用实体理解系统；
- Location 不是通用地理知识平台；
- Provenance 主要记录来源类型，还不是完整 source-turn / causal tracing；
- 相对 Critique 的具体锚点策略要按当前真实代码讲，不能背“P25 类目均价、脉冲清零”等未被仓库事实确认的机制；
- Runtime Event 的 durable/best-effort 也要按真实事件类型表讲，不能概括成“所有 TOOL_CALL 都和状态写入同事务”。
