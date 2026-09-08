# 多轮状态与 Working Memory——简历驱动追问链

## 0. 如果简历要写这一点，怎么写

建议简历 bullet：

> 设计多轮消费决策 Working Memory，将 Chat History 与当前业务状态解耦，维护预算、菜系、地点、候选商户及任务上下文；支持条件增量修改、显式清除、历史方案切换与推荐引用，并通过版本化状态、OCC 与运行时版本校验避免旧状态和慢工具结果污染当前会话。

这句话主动暴露的关键词是：`Working Memory`、多轮状态、条件修改/清除、历史方案、OCC。不要在简历上继续把 TurnCommandSet、RecommendationBatch、Location Contract、Provenance 等内部名词全部倒出来，它们应该作为被追问后的深挖证据。

---

# 一、先看这一条线到底是怎么长出来的：开发演进速览

这条线不是一开始就“设计了一个 Working Memory”。相反，项目早期根本没有清晰的 Working Memory 抽象，后面几乎整个开发周期都在围绕“当前状态到底是什么、谁有权改、怎么避免旧状态污染”不断收口。

先把整条演进链记住：

```text
没有明确 Working Memory
↓
状态散落在 Chat History / 当前 Context / 候选列表 / 会话字段
↓
先解决 candidatePool 与 shownShopIds 混淆
↓
引入 RecommendationBatch 保存历史批次和 ordinal
↓
形成 Flat Working Memory
↓
A → B → A 暴露 Ghost Inheritance
↓
Working Memory V2：Task-scoped State
↓
Task V2 初版仍失败：同一 Turn 里重新 load 旧 snapshot
↓
统一 request-scoped authoritative snapshot
↓
继续暴露 Slot Mutation 问题：未提及 ≠ 显式清除，Mention ≠ Mutation
↓
增加 clearedFields / removedPreferences / Turn Understanding
↓
继续暴露 Location State 问题：Value、Intent、Provenance、Projection 混淆
↓
locationIntent / searchLocation / 行政层级 / POI Grounding 收口
↓
并发问题：version / OCC 防 Lost Update
↓
慢 Tool 问题：baseWorkingMemoryVersion 防 stale runtime result
↓
可解释性问题：当前 Value、来源 Provenance、Historical Decision Fact 分离
```

如果面试官问“你这个 Working Memory 是怎么设计出来的”，不要从最终类结构倒背。应该从这条时间线往下讲。

---

## 阶段 1：最开始其实没有 Working Memory

早期系统更接近：

```text
Chat History
+ 当前请求 ChatProcessingContext
+ 当前 candidate list
+ focused shop
+ 若干 session 字段
```

简单对话还能工作：

```text
福州火锅，人均100
→ 预算改成80
```

但一进入“换一批”“之前那家”“切到另一套需求”“再切回来”，状态开始散落，谁是当前真值并不清楚。

这一阶段最重要的认识不是“要不要存 Memory”，而是：

> Chat History 是语言证据，不应该同时承担当前业务真值。

---

## 阶段 2：先从候选集合开始拆语义

最早比较直接的问题是：

```text
第一批：A B C
用户：换一批
第二批：D E F
```

如果只有一个 candidatePool，第二批会覆盖第一批；但如果只保存“所有看过的店”，又无法知道当前哪些候选仍然有效。

于是先区分：

```text
candidatePool
= 当前条件下仍可继续决策的候选

shownShopIds
= 用户历史上已经看过的商户
```

但随后又发现：

```text
shownShopIds = [A,B,C,D,E,F]
```

仍然回答不了：

```text
“最开始第二家”是哪家？
```

因为 flat shown list 没有 batch boundary 和 ordinal。

所以继续引入：

```text
RecommendationBatch 1 = [A,B,C]
RecommendationBatch 2 = [D,E,F]
```

并把 batch 绑定当时的 `decisionSessionId`。这一步解决的是：

```text
当前 Candidate Projection
≠
历史展示事实
≠
历史批次顺序
```

---

## 阶段 3：有了 Flat Working Memory，但 A→B→A 还是会串

后来状态开始被收拢进 Working Memory，但最初仍是一份扁平状态：

```text
WorkingMemory
├─ activeCriteria
├─ searchLocation
├─ candidatePool
├─ focusedShop
└─ dialogPhase
```

简单 refinement 没问题：

```text
福州火锅100
→ 预算改80
```

但 robustness 很快暴露：

```text
Task A：福州 / 火锅 / 100
Task B：杭州 / 日料 / 300
用户：还是最开始那套
```

如果只有一份 flat criteria，就很容易得到：

```text
福州 + 火锅 + 300
```

甚至残留杭州/西湖字段。

这就是开发记录里反复出现的 Ghost Inheritance：旧任务里的预算、地点、菜系像“幽灵”一样渗入当前任务。

这时已经不是再补几条 merge rule 能解决的，因为 representation 本身错了。

---

## 阶段 4：Working Memory V2——引入 Task Scope

于是 V2 不再把所有条件都放在全局 Working Memory，而是：

```text
ConversationWorkingMemory
├─ activeTaskId
└─ tasks[]
    ├─ criteria
    ├─ constraintSources
    ├─ searchLocation
    └─ recommendationBatches[]
```

于是：

```text
Task A
= 福州 / 火锅 / 100

Task B
= 杭州 / 日料 / 300
```

用户说“回到最开始那套”时，不是拿 B 的当前字段重新拼一个 A，而是重新激活 A。

这里要记住一句：

> 历史 Working Memory Version 表示“过去某个时刻是什么状态”；Task 表示“当前会话里仍然具有业务身份的独立方案”。

这两者不是一回事。

---

## 阶段 5：Task V2 写完了，为什么 Scope 还是没变好？——同轮双快照 Bug

这是这条线最值得面试讲的真实事故之一。

V2 第一版后，Task transition 的单测可以通过，但完整 Conversation Evaluation 里 Scope 仍然只有部分通过。逐轮看发现：

```text
taskCount 一直是 1
activeTaskId 也没真正切换
```

最后发现不是 Task 判断错，而是一个请求内部存在两个 Working Memory 世界：

```text
transitionTask()
→ 修改 request-scoped Working Memory

后面 reduceCriteria()
→ 又从数据库 reload Working Memory
→ 拿到 transition 之前的旧 snapshot
→ 新 Task 被旧状态覆盖
```

最后确定不变量：

> 一个 Turn 内只能有一份 authoritative Working Memory snapshot。

正确链路：

```text
Bootstrap load
→ task transition
→ criteria merge
→ reduce
→ persist
```

全部围绕 `context.getWorkingMemory()` 这一份 request-scoped snapshot 工作，中间不重新 load 一个“同版本但更旧的世界”。

这一步非常重要，因为它说明：

```text
Task Model 对
≠
Pipeline State View 一定对
```

数据模型和执行时序都要一致。

---

## 阶段 6：有了状态，还得定义“这一轮到底是在改，还是只是在提到”

Working Memory 稳定后又出现另一类错误：

```text
用户：这个日料怎么样？
Extractor：cuisine = 日料
```

如果系统采用：

```text
Delta 非空
→ 认为用户要修改状态
```

就会把事实追问误写成搜索条件。

于是形成一个核心不变量：

> Mention ≠ Mutation。

最终职责开始拆成：

```text
Turn Understanding
→ 决定“这一轮有没有修改状态的权限”

Constraint Extractor
→ 如果允许修改，具体改什么

Criteria Merger / State Service
→ 怎么合并并持久化
```

也就是说，Extractor 不再既当“语义解析器”又当“状态写权限裁判”。

---

## 阶段 7：未提及、清除、删除偏好，不能都用 null 表达

随后又遇到更细的状态生命周期问题。

比如上一轮：

```text
budget = 100
preference = 安静
```

这一轮用户什么都没说预算：

```text
budget 未提及
→ 应该继承100
```

但用户说：

```text
“不限预算”
→ 应该主动清除 budget
```

两者如果都表示成 `null`，Reducer 根本不知道：

```text
这是模型没抽到？
还是用户明确要求删掉？
```

所以协议增加：

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

这条可以概括成：

> Value 和 Mutation Operation 必须分开建模。

`null` 不能同时代表“absent”和“clear”。

---

## 阶段 8：Location 把 Working Memory 的状态边界继续逼深

Location 是 Working Memory 主线非常好的深挖案例，因为它连续暴露了多层状态语义问题。

最早会出现：

```text
设备 GPS 在福州
用户明确说：北京有啥好吃的
```

如果代码只看 request.city / 经纬度，很容易把“用户设备在哪”和“这套 Task 想去哪搜”混在一起。

后面又出现：

```text
重庆 / EXPLICIT_TARGET
→ 用户：我附近
```

如果只设置 nearby=true，却不清掉 targetCity=重庆，就形成：

```text
重庆 + 当前设备附近
```

这种不可能状态。

因此进一步形成：

```text
Value
≠ Intent
≠ Provenance
≠ Projection
```

并增加 `locationIntent`：

```text
EXPLICIT_TARGET
CURRENT_DEVICE
UNSPECIFIED
```

后来“福建”又暴露行政层级缺失：如果只有 targetCity，`福建` 会被错误投影成 city='福建'。于是 province/city/district/area 逐步进入 Canonical State。

“师大附近”又暴露：

```text
Location Mention
≠ Verified Geographic Identity
```

地图搜索只是 candidate retrieval，不应该把第一条结果直接持久化成 searchLocation。

所以 Location 最终变成一个很好的总结：

> Working Memory 不是“把自然语言变成 JSON”，而是要保存后续业务决策真正依赖的语义维度、来源与执行含义。

---

## 阶段 9：单请求一致了，还要处理跨请求并发——OCC

同一 Turn 一份 snapshot，只解决单请求内部一致性。

如果：

```text
请求 A 基于 version=20
请求 B 也基于 version=20
B 先提交 version=21
A 再提交
```

A 不能静默覆盖 B。

因此 Working Memory append 使用 version / expectedVersion 做乐观并发控制：旧版本写入直接失败，而不是覆盖较新的 state。

这里别简单讲成“完全不用锁”。真正应该讲的是：

```text
单 Turn
→ request-scoped snapshot

跨请求 Durable Mutation
→ version / OCC
```

如果上层还有 session-level 串行化，也不要回避；OCC 仍然是底层最后一道 Lost Update 防线。

---

## 阶段 10：OCC 还不够——慢 Tool 的 stale runtime result

更隐蔽的是：

```text
A 基于 WM V20 开始调用 Tool
Tool 执行 8 秒
期间用户新消息把 WM 推进到 V21
Tool 返回
```

即使最终持久化时 OCC 能阻止 V20 覆盖 V21，A 的 Tool Result 本身已经是基于旧世界算出来的。

所以 runtime context 保存：

```text
baseWorkingMemoryVersion
```

Tool / Agent 执行完后重新看最新版本。如果：

```text
base=20
latest=21
```

则标记 stale runtime result，不允许它再回写 candidatePool、focusedShop、criteria 等 canonical state。

可以记成三层：

```text
same-turn snapshot
→ 防一个请求里看到两个世界

OCC
→ 防两个请求互相覆盖

stale runtime guard
→ 防慢 Tool 用旧世界污染新状态
```

---

## 阶段 11：最后连“为什么当时推荐这家”也不能拿当前 WM 重解释

Working Memory 做到后面，还出现用户内省：

```text
“我之前说过安静吗？”
“这个预算是我说的吗？”
“为什么当时推荐第一家？”
```

此时只有当前 Value 不够。

所以逐步拆成：

```text
Value
→ 当前生效什么

Provenance
→ 这个值来自 USER_EXPLICIT / DERIVED / SYSTEM_DEFAULT

Historical Decision Fact
→ 当时推荐结果与理由是什么
```

历史推荐解释不能拿当前 criteria 重跑一遍，而是通过：

```text
Resolved Reference
→ RecommendationBatch.decisionSessionId
→ historical DecisionSession.resultJson
→ 当时的 Recommendation / matchedReasons / evidence
```

这样避免 Temporal Leakage：

```text
今天的条件
不能穿越回去解释昨天的推荐
```

---

# 二、面试追问链

## 1. 为什么不直接把 Chat History 全交给大模型？

### 40 秒回答

> Chat History 记录的是“发生过什么”，Working Memory 记录的是“当前哪些业务事实仍然成立”。多轮里会有覆盖、清除、任务切换和恢复，例如福州火锅100，后来切到杭州日料300，再说回到最开始那套。如果每轮都让模型从 History 重推当前状态，本来应该确定的状态合并会变成概率问题。所以 History 保留语言证据，Working Memory 保存当前 Task、criteria、searchLocation、candidate projection、focus 等 canonical business state。

### 继续追

- 摘要 Memory 不行吗？
- 为什么不是把所有 Tool Result 一起放进去？
- Working Memory 为什么要持久化？

---

## 2. Working Memory 里到底存什么？

重点答“当前业务真值”，不要背字段表。

可以说：

```text
当前 active Task
Task-local criteria
constraintSources
searchLocation
当前 candidate projection / focused shop
RecommendationBatch 的历史轻量索引
version / active decision reference
```

不存完整评论、完整 Tool payload、全部历史 evidence，因为那些属于执行事实或历史决策事实，不应该不断复制进 versioned full snapshot。

---

## 3. Slot / Constraint 是怎么更新的？

面试官常从最简单的问：

```text
“人均100” → “150也可以”
```

这里本质是：

```text
Extract Delta
→ Merge with Previous
→ Persist Canonical State
```

但真正难的是后面的 operator：

```text
SET
CLEAR
REMOVE
EXCLUDE
RELATIVE CHANGE
NO MUTATION
```

所以不要把这一块讲成普通 Slot Filling。

---

## 4. “没说预算”和“不限预算”怎么区分？

这是应该重点准备的一题。

```text
未提及 budget
→ inherit previous

“不限预算”
→ clearedFields += budget
→ clear previous budget
```

同理：

```text
“不要安静了”
→ removedPreferences += 安静
```

### 为什么不能 null？

因为 null 无法同时表达：

```text
模型没抽到
vs
用户明确清除
```

一句话：

> absent 是“没有操作”，clear 是一种明确操作。

---

## 5. “这个日料怎么样”为什么不能把 cuisine 改成日料？

因为：

```text
Entity Mention
≠ State Mutation
```

Extractor 能识别“日料”只是说明文本里有这个语义材料，不代表用户允许修改搜索条件。

所以现在分三层：

```text
Turn Understanding：能不能改
Extractor：具体改什么
Merger / Reducer：怎么落
```

### 为什么 Prompt 里约束模型还不够？

Prompt 只能降低错误概率，不能成为 canonical state mutation 的最终 authority。

---

## 6. 为什么条件变化以后还要清 candidatePool / focusedShop？

因为 candidatePool 是某一组 retrieval domain 产生的派生投影。

例如：

```text
原条件：川菜
候选：A/B/C

用户：换粤菜
```

即使 A/B/C 这些 Shop 仍然存在，它们也不再是“当前条件下有效的候选”。

所以 Search Domain 关键字段变化后：

```text
invalidate current candidate projection
clear focused shop
```

但历史 RecommendationBatch 要保留，因为“用户过去看过 A/B/C”仍然是历史事实。

一句话：

> Current Projection 可以失效，Historical Fact 不能被抹掉。

---

## 7. 为什么 Flat Working Memory 不够？

拿 A→B→A 讲。

```text
A：福州火锅100
B：杭州日料300
回到 A
```

Flat WM 只有一份 active criteria，很容易出现 budget=300 或西湖位置残留。

所以 V2 引入 Task Scope，每个 Task 自己保存 criteria / searchLocation / batches。

### 为什么不用历史 version 直接恢复？

历史 version 表示过去时刻；Task 表示当前会话中仍然有身份的方案。版本是时间维，Task 是业务身份维。

---

## 8. Task 怎么决定 UPDATE / CREATE / ACTIVATE？

当前不是通用任务管理框架，而是餐饮领域 heuristic。

可以讲：

```text
预算100 → 150
通常 UPDATE 当前 Task

显式“回到最开始/之前那套”
→ ACTIVATE historical task

目标地点 + 主菜系同时明显切换
→ 更倾向 CREATE 新 Task
```

不要吹成通用 Task Planner。

---

## 9. “最开始第二家”怎么解析？

不能只靠 current candidatePool，也不能只靠 flat shown list。

```text
第一批 A/B/C
第二批 D/E/F
“最开始第二家”
→ earliest RecommendationBatch
→ ordinal 2
→ B
```

RecommendationBatch 保存 batch boundary、顺序和 decisionSessionId。

继续追可能会问：

```text
第一家
上一批第一家
最开始第二家
日本料理那个
这个
```

这里重点讲 resolution contract，不要一上来背 Resolver 类名。

---

## 10. 为什么 Task V2 写完还会失败？

讲 same-turn snapshot Bug。

```text
前面 transition 修改 WM
后面又 reload DB 旧 snapshot
新状态被旧状态覆盖
```

所以一个 Turn 只能有一份 authoritative snapshot。

这是这条线最能证明“你真的调过系统”的问题之一。

---

## 11. 两个并发请求怎么防 Lost Update？

用 OCC：

```text
A base v20
B base v20
B → v21 成功
A 再写 → expectedVersion mismatch → reject
```

为什么不用只靠悲观锁：OCC 让冲突显式暴露，不需要把长耗时模型/工具执行全程包在数据库锁里。但不要否认上层可能仍有 session serialization；两者解决的层次不同。

---

## 12. Tool 很慢，回来时状态已经变了怎么办？

用 `baseWorkingMemoryVersion`。

```text
Tool 基于 v20
返回时 latest=v21
→ stale runtime result
→ 禁止旧结果回写 canonical state
```

只读结果如果对当前回复仍安全，可以用于回复；但不能继续更新候选池、focus、criteria。

---

## 13. Location 为什么是 Working Memory，而不只是地图问题？

因为它暴露的是状态建模：

```text
设备位置
≠ 搜索目的地

city value
≠ 来源 provenance

出现“师大”
≠ 已解析 geographic identity

有经纬度
≠ 当前一定按附近搜索
```

最适合讲的 Case：

```text
GPS 在福州
用户说北京农大附近
```

和：

```text
重庆无数据
用户说“那我附近呢”
```

前者显式目的地压过设备上下文；后者最新用户意图又必须允许从 EXPLICIT_TARGET 切到 CURRENT_DEVICE。

---

## 14. 搜不到候选为什么不能统一“放宽条件”？

因为零结果也有不同语义：

```text
有预算/菜系/半径等可松弛约束
→ WAITING_RELAXATION

只指定了合法城市，但业务库里没数据
→ ZERO_RESULT_NO_DATA
```

如果没有任何可松弛项，却告诉用户“放宽条件”，状态机语义就是错的。

而且放宽 hard constraint 必须来自用户显式同意，不能系统偷偷扩大范围。

---

## 15. “为什么推荐这家”“这个预算是我说的吗”怎么回答？

当前 Value 不够，还需要：

```text
constraintSources
→ USER_EXPLICIT / DERIVED / SYSTEM_DEFAULT
```

历史推荐理由则走 historical Decision Fact，不拿当前 WM 重解释：

```text
RecommendationBatch.decisionSessionId
→ historical DecisionResponse
→ matchedReasons / evidence
```

这解决 Temporal Leakage。

---

# 三、最值得背熟的真实事故

如果只能准备 5 个，优先这几个：

### 事故 1：A→B→A Ghost Inheritance

证明为什么 Flat WM 不够，为什么需要 Task Scope。

### 事故 2：Task V2 仍失败，因为同一 Turn reload 旧 snapshot

证明你理解 Pipeline state consistency，而不只是会设计 DTO。

### 事故 3：“这个日料怎么样”污染 cuisine

证明 Mention ≠ Mutation，Extractor ≠ Mutation Authority。

### 事故 4：重庆 → “我附近”仍残留 EXPLICIT_TARGET

证明 Value / Intent / Provenance / Projection 要分离。

### 事故 5：Tool 基于 V20，返回时 WM 已 V21

证明 OCC 与 stale runtime result 是两层问题。

---

# 四、这条线怎么在面试里收口

1 分钟版本：

> 这个 Working Memory 不是一开始设计出来的。最早系统主要依赖 Chat History、当前 Context 和一些候选字段，简单多轮可以跑，但换一批、历史引用和多方案切换后状态开始串。我们先拆 candidatePool 和 shown history，再加 RecommendationBatch 保存批次和 ordinal；后来 Flat Working Memory 在 A→B→A 场景出现 Ghost Inheritance，所以引入 Task-scoped State。Task V2 第一版又暴露一个很隐蔽的问题：同一请求前半段修改了 Working Memory，后半段重新从数据库读旧 snapshot，把新 Task 覆盖掉，于是又收敛成一个 Turn 只有一份 authoritative snapshot。后面继续补了 clearedFields/removedPreferences 区分未提及和主动清除，Turn Understanding 解决 Mention ≠ Mutation，Location 又逼着我们把 Value、Intent、Provenance、Projection 分开。并发层用 version/OCC 防 Lost Update，慢 Tool 再用 baseWorkingMemoryVersion 防 stale result 回写。最后 Working Memory 也不再承担所有历史事实，历史推荐理由通过 RecommendationBatch 指向当时的 DecisionSession 回查。

这段讲完以后，面试官无论往 Task、Slot、Location、OCC、Reference 还是 Evaluation 追，你都有下一层。

---

# 五、当前不能吹什么

- Working Memory 当前是 versioned full snapshot，不是完整 Event Sourcing；
- Task Identity 是餐饮业务 heuristic，不是通用 Task Planner；
- Turn Semantics 仍是当前业务所需的轻量 command boundary，不是完整 Dialogue Act/DSL；
- Location 仍依赖行政 Resolver、Provider 和 POI resolution，不是通用地理知识平台；
- 不能说所有并发都只靠 OCC，也不能说 session-level serialization 完全不存在；
- 复杂 compound turn、部分自然语言恢复和某些 Context Rewrite 边界仍然存在；
- Provenance 当前主要记录来源类型，不要夸成完整 source-turn / causal tracing 系统。
