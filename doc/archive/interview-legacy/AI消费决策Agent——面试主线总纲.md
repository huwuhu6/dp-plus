# AI 消费决策 Agent——面试复习手册（问题驱动版）

> 这份资料按“小白也能顺着看懂”的方式重写。不要先背 Working Memory、Task、Turn Semantics 这些名词，而是先看系统遇到了什么问题，再看为什么原方案不行，最后自然引出对应设计。
>
> 当前 Code Truth：main `8611c2fc65f0470ed18bb5b84171ad8520f88359`
>
> 当前归档验证：Unit Tests `402 / 0 failures / 0 errors / 3 skipped`；Robustness Run144、Conversation-v1 Run145、Holdout Run146。
>
> 每个问题统一按照：**现象 → 第一版做法 → 为什么失败 → 最终方案 → 面试回答 → 可能追问** 来讲。

## 项目主线：先把项目本身讲明白

**Q1：这个项目最开始到底想解决什么问题？如果只做最简单版本，会怎么做？**

最开始的目标其实很简单：用户说自己想吃什么、预算多少、在哪附近，然后系统给出几家餐厅。

如果只做一个 Demo，最简单的链路完全可以是：

```text
用户输入
→ 把问题发给大模型
→ 大模型提取“地点、预算、菜系”
→ 查数据库/向量库
→ 把结果再交给大模型生成答案
```

单轮时，这套方案没什么问题。真正麻烦的是用户不会每次都把所有条件重说一遍，而且会不断反悔、切换、追问。

例如：

```text
用户：福州附近想吃火锅，人均100
用户：预算可以到150
用户：换成杭州日料吧
用户：还是最开始那套
用户：最开始第二家怎么样？
```

这时候系统已经不是“问一句答一句”，而是在维护一个持续变化的消费决策过程。

所以项目后来的重点也从“推荐餐厅”变成了：

```text
怎么记住当前条件
怎么切换不同方案
怎么恢复历史方案
怎么理解“第一家/最开始第二家”
怎么防止用户只是问问题却把条件改掉
怎么确保地点、Tool、历史事实都不乱
```

> **面试回答：**
> 我做的是一个本地生活餐饮消费决策 Agent。最初只是想做“用户描述需求 → 推荐餐厅”，但真实多轮对话很快暴露出状态串扰、历史引用漂移、反悔条件、地点歧义、Tool 事实不可靠等问题。后来项目重点从单轮推荐变成了多轮决策状态管理：模型负责理解开放语义，Java 负责状态、实体确认、动作合法性和 Tool 执行。

**可能追问：为什么叫 Agent，不叫 ChatBot？** 后面 Q25 会专门回答。

---

**Q2：为什么不能把全部聊天记录都丢给大模型，让它自己记住用户现在想要什么？**

最开始最自然的想法就是：每一轮都把 Chat History 一起发给模型。

比如：

```text
用户：想吃火锅，人均100
用户：预算提高到150
```

模型大概率能理解“火锅”还在，预算现在是150。

问题出在对话开始复杂以后：

```text
福州火锅100
→ 预算改成150
→ 换成杭州日料300
→ 还是最开始那套
```

聊天记录只能告诉系统：

```text
这些话都说过
```

但系统真正需要知道的是：

```text
现在激活的是哪套需求？
当前地点是什么？
当前预算是多少？
哪些旧条件已经失效？
```

如果每轮都让 LLM 从整段 History 重新推断，就把一个本来应该确定的问题变成了概率问题。

因此后来增加了一份结构化的“当前业务状态”，专门保存系统现在真正认可的地点、预算、菜系、当前任务等信息。这份状态后来叫 `Working Memory`。

可以先这样理解：

```text
Chat History
= 记住“发生过什么”

Working Memory
= 记住“现在到底是什么”
```

> **面试回答：**
> Chat History 是语言证据，不是当前业务真值。它能告诉我用户以前说过什么，但不能稳定表达哪些条件现在仍然生效。如果每轮都让 LLM 重新从 History 推断当前状态，同一段历史可能得到不同结果，而且覆盖、清除、恢复、并发版本都不好做。因此我把 History 和业务状态分开：History 保留语言上下文，Working Memory 保存当前确定的业务状态。

**可能追问：那摘要 Memory 行不行？** 摘要可以压缩历史，但摘要仍然是自然语言，不适合替代预算、Task、版本号这类必须确定的业务状态。

---

**Q3：Working Memory 具体解决了什么？它里面应该存什么，不应该存什么？**

Working Memory 不是“把所有历史都保存一遍”，而是保存当前业务真正需要的状态。

当前主要有：

```text
当前激活的任务
当前地点/设备位置
当前约束：预算、菜系、偏好等
当前 focus 的商户
历史推荐批次的轻量引用信息
当前决策阶段
版本号相关状态
```

它不应该把所有评论、完整 Tool 返回、所有大模型输出都复制进去，因为这样会越来越大，也会出现多份历史事实互相不一致。

例如某次推荐为什么选了 A 店，这属于“当时那次执行的历史事实”，更适合放在 `DecisionSession`；而“当前预算是100”才属于 Working Memory。

> **面试回答：**
> Working Memory 我把它定义成当前 canonical business state，也就是系统此刻真正相信什么。它保存当前 Task、criteria、searchLocation、focus 等状态，但不会复制所有历史 Tool Result 和长文本 evidence。历史执行事实放 DecisionSession，历史推荐身份和顺序放 RecommendationBatch。这样不同数据各自只有一个 authority，避免同一事实在多个地方重复存储。

---

**Q4：既然有 Working Memory 了，为什么后来还要再搞 Task？**

一开始 Working Memory 里只有一套当前条件。

真实对话后来出现：

```text
福州火锅100
→ 杭州西湖日料300
→ 还是最开始那套
```

如果只有一份 Flat Working Memory，系统很容易把条件拼成：

```text
福州 + 火锅 + 西湖 + 300
```

一开始很容易把这个问题理解成“字段覆盖规则写得不够好”，于是继续补 merge / clear 规则。

但后来发现根因不是 merge，而是用户实际上同时维护了两套独立需求：

```text
方案A：福州 / 火锅 / 100
方案B：杭州 / 日料 / 300
```

所以最终不再把所有条件当成“一套状态不断修改”，而是在会话里维护多个 Decision Task。

用户说“回到最开始那套”时，本质是：

```text
重新激活 Task A
```

而不是重新把几年前面的字段再拼一次。

> **面试回答：**
> 我最开始把多轮状态问题当成 criteria merge 问题，后来 A→B→A 的真实对话让我发现 representation 本身就错了。用户可能同时维护多套消费方案，所以 Flat Working Memory 不够。我把它改成 tasks[]，每套方案有自己的 criteria 和 searchLocation。恢复历史方案是重新激活原 Task，而不是拿当前状态重新拼字段。

**可能追问：为什么预算不作为 Task Identity？** 因为预算通常只是 refinement。否则福州火锅100、80、120会碎成三个 Task。当前 Task Identity 是餐饮业务启发式，不是通用任务管理框架。

---

**Q5：用户“换一批”以后，为什么只保存 candidatePool 还不够？**

一开始系统只需要记住“当前这几家候选”。

例如：

```text
第一批：A / B / C
用户：换一批
第二批：D / E / F
```

如果只保留当前 candidatePool，那么换完以后 A/B/C 就丢了。

后来即使加了 `shownShopIds=[A,B,C,D,E,F]`，仍然解决不了：

```text
用户：最开始第二家怎么样？
```

因为系统只知道“看过谁”，不知道：

```text
哪一批？
第几个？
```

所以才增加 `RecommendationBatch`：每一轮推荐都保存自己的候选顺序和对应 decisionSessionId。

可以理解成：

```text
candidatePool
= 现在屏幕上这批是谁

RecommendationBatch
= 历史每一批是谁、顺序是什么
```

> **面试回答：**
> candidatePool 只能表示当前候选，shownShopIds 只能表示“展示过谁”，都丢失了 batch boundary 和 ordinal。因此我增加 RecommendationBatch，保存每轮推荐的 candidates 顺序和 decisionSessionId。这样“最开始第二家”“上一批第一家”都能稳定回到对应历史批次。

---

**Q6：用户说“第一家”“最开始第二家”“日本料理那个”，系统到底怎么知道指的是谁？**

这是“引用解析”问题。

最开始的简单做法可能是：

```text
第一家 → 当前列表 index=0
这个 → focusedShopId
```

但真实对话会越来越复杂：

```text
最开始第二家
上一批第一家
这个日本料理
那个烧烤
刚才那家
```

如果每个业务 Handler 都自己解释一次，很容易出现不同地方的“第二家”含义不一致。

最终做法是先把自然语言里的引用单独解析出来，再统一去历史 RecommendationBatch 里找真正的商户。

尤其后来“这个日本料理”暴露了一个坑：

```text
A：东北菜（当前 focused）
B：日本料理
用户：这个日本料理重口吗？
```

如果只看到“这个”就直接用 focused=A，就会绑错店。

所以现在引用优先级更像：

```text
明确序号
> 唯一描述性限定词（日本料理/烧烤）
> 纯“这个/那家”才回退 focused
```

> **面试回答：**
> 我把 reference 从业务 Handler 里抽出来统一 Grounding。ordinal reference 会结合 RecommendationBatch 解析“最开始第二家”；描述性 reference 会结合 candidate 的轻量 metadata 匹配“日本料理那个”。只有纯 deictic 的“这个/那家”才回退 focusedShopId。这样引用身份只有一个 resolver authority，不会每个分支各自猜一次。

---

**Q7：为什么“第一家太贵了，第二家有插座吗？”会逼着系统从单 Intent 变成更复杂的 TurnPlan？**

这句话里同时有两个动作：

```text
第一家太贵了
→ 用户在表达对当前条件/候选的不满意，可能触发条件调整

第二家有插座吗？
→ 用户又在追问另一个商户事实
```

如果系统规定：

```text
一个 Turn 只能有一个 Intent
```

那就必须二选一，必然丢掉另一半语义。

于是后来把“一轮要不要改条件”和“一轮要执行什么动作”拆成两个维度：

```text
CriteriaIntent
= NONE / APPLY_DELTA

ExecutionAction
= 本轮真正执行的业务动作
```

这样同一句话可以同时表达：

```text
改条件 + 问事实
```

> **面试回答：**
> 单 Intent 模型无法表达 compound turn。比如“第一家太贵了，第二家有插座吗”同时包含 Criteria Mutation 和 Business Follow-up。因此我把 TurnPlan 拆成 CriteriaIntent 和 ExecutionAction 两个正交维度。这样不用不断增加“修改条件并查询商户”这种组合型 Intent。

---

**Q8：有了 Working Memory，为什么还会出现“这个日料咋样？”把 cuisine 改成日料这种错误？**

这是整个项目里很关键的一次认识。

用户说：

```text
这个日料咋样？
```

模型完全可能正确抽取到：

```text
cuisine = 日料
```

但“识别到日料”不代表用户在说：

```text
把搜索条件改成日料
```

他可能只是用“日料”描述某家店。

如果系统的逻辑是：

```text
只要 Extractor 抽到字段
→ 就认为是 mutation
```

那模型每识别到一个实体，都可能偷偷修改状态。

所以后来增加了“本轮状态写权限”的概念：先判断用户这一轮到底有没有修改条件的意图，再决定 Extractor 的 Delta 能不能落到 Working Memory。

这就是后来所谓的 `Canonical Turn Semantics`。

先别背英文，直接记住一句：

```text
提到了某个条件
≠
要求修改这个条件
```

> **面试回答：**
> Working Memory 只能解决“当前状态是什么”，不能解决“谁有权改这个状态”。真实对话“这个日料咋样”让我发现 Mention 和 Mutation 必须分开。现在 TurnUnderstanding 先判断本轮是否真的存在 mutation intent；只有获得写权限后，ConstraintExtractor 提取的 Delta 才能进入 Reducer。也就是说 Extractor 可以告诉系统“文本里有日料”，但它没有状态写权限。

**可能追问：为什么第一版 Turn Semantics 还返工？** 因为当时 selectAction 已经走新 authority，但 route 里还残留旧 `hasMutation(criteriaDelta)`，实际形成两个 Mutation Authority。后来统一到同一处。

---

**Q9：为什么同一个请求内部还会出现“新状态被旧状态覆盖”？**

这是一个很隐蔽的 Bug。

当时 Task 逻辑单测没问题，但端到端失败。最后发现同一请求中：

```text
transitionTask()
→ 已经把当前 request 里的 Working Memory 改成新状态

reduceCriteria()
→ 又从数据库重新 load 了一份旧 Working Memory
```

于是同一个 Turn 里同时出现：

```text
一个新世界
一个旧世界
```

后面的逻辑又把旧世界写了回去。

最终规定：

```text
一个 Turn 只能有一份 authoritative Working Memory snapshot
```

也就是 Bootstrap 时 load 一次，后面的 transition、merge、reduce、persist 都围绕这同一份 request-scoped snapshot 做。

> **面试回答：**
> Task V2 有一次典型问题：前半段已经修改了当前请求里的 WM，后半段又从 DB reload 同 version 的旧 WM，导致同一请求出现两个世界。后来我规定一个 Turn 只能有一个 authoritative WM snapshot，从 Bootstrap 到 persist 都围绕同一份 request-scoped state 工作。

---

**Q10：如果两个请求同时修改同一个会话，怎么防止旧请求覆盖新请求？**

同一个请求内部只有一份 snapshot，还不够解决并发。

例如：

```text
Turn A 基于 version=20
Turn B 也基于 version=20

B 先写成功 → version=21
A 再写
```

如果没有并发控制，A 会把自己基于旧世界算出来的状态覆盖掉 B。

这里使用的是乐观并发控制（OCC）：写入时检查 expectedVersion。

```text
expectedVersion != latestVersion
→ VersionConflictException
```

> **面试回答：**
> same-turn snapshot 解决的是单请求内部一致性，跨请求并发我用 OCC。每次 Working Memory append 都带 expectedVersion，只有版本匹配才能写下一版。这样旧 Turn 不能静默覆盖已经提交的新状态。

---

**Q11：已经有 OCC 了，为什么还要防“慢 Tool 的旧结果”？**

OCC 只能防止“旧状态写数据库”，但 Tool Result 还有另一个问题。

例如：

```text
Turn A 基于 v20 调一个 5 秒的 Tool
Turn B 很快把状态更新到 v21
5 秒后 Turn A Tool 返回
```

虽然 A 最后可能写 WM 时被 OCC 拒绝，但 A 的 Tool Result 本身已经是：

```text
基于旧条件 v20 算出来的结果
```

如果系统还把它继续当成正确事实展示给用户，也是不对的。

所以 runtime context 会记住 Tool 开始时的 `baseWorkingMemoryVersion`。Tool 返回后再检查当前 latestVersion，如果已经变化，则拒绝这个旧结果。

> **面试回答：**
> OCC 防的是旧写覆盖新状态，但慢 Tool 还会产生“基于旧世界计算出来的结果”。因此我额外保存 baseWorkingMemoryVersion，Tool Result 回写前再次和 latestVersion 比较，不一致就标记 STALE_RUNTIME_RESULT 并拒绝继续使用。

一句话记忆：

```text
same-turn snapshot → 单请求世界一致
OCC                → 并发写一致
stale guard        → 慢 Tool 结果一致
```

---

**Q12：为什么不直接把所有状态变化都做成 Event Sourcing？为什么现在用 Full Snapshot？**

Event Sourcing 听起来很适合“有历史版本”的系统，但它意味着当前状态主要靠事件 replay 重建。

这个项目现在不是这么做的。

当前 Working Memory 状态量还不大，而且最常见需求是：

```text
直接读当前状态
恢复某个历史 snapshot
快速 debug
```

所以每一版保存完整 snapshot 更简单。

ConversationEvent 主要用于审计和诊断，不承担“重建所有业务状态”的职责。

> **面试回答：**
> 当前我选择 Full Snapshot 而不是 Event Sourcing，因为 Working Memory 规模可控，读取、恢复和 debug 都更直接。ConversationEvent 用来做 audit/diagnostics，而不是靠 replay 重建状态。代价是长会话存储会增长，未来如果真的成为瓶颈，再考虑 periodic full + delta/compaction，而不是提前引入完整 Event Sourcing 复杂度。

---

**Q13：DecisionSession 和 Working Memory 都有条件，为什么不会变成两份“真相”？**

这个问题在“无结果放宽”时真实发生过。

例如：

```text
用户原来：兰州拉面
系统 0 结果
用户：那附近随便看看
```

执行层已经清掉了：

```text
keyword=兰州拉面
cuisine=面食
```

但后来发现：

```text
DecisionSession 里已经放宽
Working Memory 里还是旧条件
```

下一轮又会被旧 WM 污染。

但也不能简单把整个 DecisionSession.constraints 覆盖回 WM，因为 DecisionSession 里可能有执行层临时值。

所以最终使用“命令投影”：只有已经被状态机确认合法的 command，才按照白名单方式修改 canonical WM。

例如：

```text
BROADEN_FOOD_SCOPE → clear keyword/cuisine
EXPAND_RADIUS      → sync radius
INCREASE_BUDGET    → sync budget
```

> **面试回答：**
> Working Memory 是当前业务真相，DecisionSession 是某次执行事实，两者不能互相全量覆盖。真实无结果恢复中，我曾遇到 DecisionSession 已放宽但 WM 仍保留旧 cuisine 的双真相。后来改成 Command Projection：只有 FSM 已验证合法的 command 才按白名单修改 WM，避免 execution 临时值反向污染 canonical state。

---

**Q14：用户问“我什么时候说过安静？”“为什么推荐第一家？”这种问题，为什么不能直接让大模型读历史回答？**

这些问题不是“新推荐”，而是在询问系统过去的决策事实。

例如：

```text
为什么推荐第一家？
```

如果直接用当前 Working Memory 回答，可能会产生时间穿越：

```text
第一批推荐时预算100
现在预算200
```

你不能拿“现在预算200”去解释为什么历史第一批当时被推荐。

再比如：

```text
我什么时候说过安静？
```

“安静”可能不是用户直接说的，而是系统从“适合聊天”推导出来的。

所以后来增加只读的 Decision Context Query：

```text
为什么推荐
当前条件是什么
某条件来源是什么
刚才实际搜的是哪里
```

历史解释通过 RecommendationBatch 找到当时的 DecisionSession，再读当时 resultJson；当前条件则读 Working Memory。

> **面试回答：**
> 历史决策解释不能简单让 LLM 从 Chat History 重猜，否则会发生 Temporal Leakage。我的做法是把这类问题作为只读 Decision Context Query。历史推荐原因通过 `Reference → RecommendationBatch.decisionSessionId → DecisionSession.resultJson` 查当时事实；当前 criteria 直接读 WM；偏好来源则用 provenance 区分 USER_EXPLICIT、DERIVED、SYSTEM_DEFAULT。

---

**Q15：Location 为什么会成为项目里最容易出问题的一块？最开始到底哪里想简单了？**

最开始很容易把“地点”理解成一个字符串：

```text
location = 福州 / 鼓楼 / 师大 / 我附近
```

但这四个例子其实完全不是一回事：

```text
福州       → 城市行政区
鼓楼       → 可能是多个城市的区
师大       → POI 简称
我附近     → 当前设备位置
```

后来真实对话不断暴露：

```text
设备 GPS 在福州，用户却说“北京农大”
“福州大学”包含“福州”，但用户说的是学校，不是在设置城市
“师大”搜索出来餐馆“师大分店”、民宿、其他学校
POI 没解析成功时系统偷偷拿 GPS 当最终搜索中心
```

所以最终不再把“地点”当一个字符串，而是拆成完整过程：

```text
用户说了什么地点
→ 这个地点属于什么地理上下文
→ 地图召回哪些候选
→ 哪个候选才是用户真正说的实体
→ 只有确认后的实体才能成为搜索中心
```

> **面试回答：**
> Location 最开始的问题是把 GPS、行政区、POI、命名地点都压成一个 location 字段。后来我把它拆成 Location Mention → Geographic Context → Candidate Retrieval → Candidate Resolution → Canonical POI → Search Anchor。核心是不再让“字符串出现”“地图搜到”“最终搜索位置”这几个概念互相代替。

---

**Q16：设备 GPS 在福州，用户说“北京农大”，到底听谁的？**

这个 Case 很适合面试。

设备位置只是告诉系统：

```text
用户现在人在哪
```

而用户明确说“北京农大”是在告诉系统：

```text
我要搜北京的某个 POI
```

这两个信息冲突时，显式命名地点必须优先。

真实 Bug 是：“北京”这种没有“市”后缀的城市 alias，如果本地行政 registry 没命中，旧 resolver 又不调用远程 provider，于是 activeCity 为空；只要此时有福州 GPS，系统就会错误走 AROUND 福州。

后来专门增加“POI 前缀行政上下文解析”：

```text
北京农大
→ 北京被行政 Provider 验证为北京市
→ poiQuery=农大
→ TEXT search region=北京市
→ 福州 GPS 不再有权覆盖
```

> **面试回答：**
> 我的 Location authority 规则是 Explicit Named Geographic Context > Device GPS Bias。GPS 是设备位置，不是用户命名目标。比如 GPS 在福州但用户说“北京农大”，先验证“北京”这个行政前缀，得到北京市后限定地图 Text Search；只有用户没给显式地点时，GPS 才作为“我附近”或短 POI 的辅助上下文。

---

**Q17：为什么“福州大学附近”不能直接解析成“福州市 + 大学附近”？**

因为：

```text
字符串里出现“福州”
≠
用户在表达行政实体“福州市”
```

如果系统做简单 substring：

```text
query.contains("福州")
→ targetCity=福州市
```

那“福州大学”会被错误拆开。

所以行政字段不能靠普通 substring 拿写权限，而要经过：

```text
原始用户文本证据
+ POI boundary
+ AdministrativeRegionResolver/provider 验证
```

> **面试回答：**
> 行政 identity 不能用 substring 决定。“福州大学”包含“福州”，但这是一个 POI 名称。后来行政 hint 必须经过 raw-query grounding 和 POI boundary，再由 AdministrativeRegionResolver/provider 验证，模型只提供 hint，不能直接把行政字段写进 canonical state。

---

**Q18：“师大附近”为什么不是直接拿高德搜索第一条结果就行？**

这次真实聊天非常典型。

用户说：

```text
帮我看看师大附近有什么好吃的
```

提供 GPS 后，高德 Around 搜索返回过类似：

```text
贵州特色豆米火锅（师大分店）
福建农林大学
平安民宿
福州大学
```

这说明：

```text
地图搜索成功
≠
地点已经解析成功
```

高德 Search 在这里只是“候选召回器”，不是“最终实体解析器”。

中间曾经为了过滤餐馆/民宿，做过一个看似合理的方案：

```text
“师大” → 推断 UNIVERSITY
→ 非大学 POI 全过滤
```

但马上被另一个反例击穿：

```text
福建师范大学附属小学
```

名称里有“大学”，但实体是小学。如果继续补 PRIMARY_SCHOOL、MIDDLE_SCHOOL……很快就会变成自己维护一套 POI taxonomy。

于是最后回撤：

```text
名称相关度
> 类型信息只做辅助
> GPS 距离再做辅助
```

`poiType/typeCode` 还保留，但不能凭类型一票否决高名称匹配候选。

> **面试回答：**
> 我后来明确区分 Retrieval 和 Resolution。高德 Search 只负责候选召回，不能把第一条结果直接当用户目标。真实“师大”会召回餐馆、民宿等噪声。我一度尝试 UNIVERSITY hard filter，但“福建师范大学附属小学”马上证明这种 taxonomy gate 会误杀。最后改成名称相关度优先，type 和 GPS 只做辅助排序，canonical POI 未确认就继续澄清。

---

**Q19：用户已经说“我说的是福建师范大学”，为什么系统以前还会继续搜“师大”？**

旧逻辑里有一个优先级问题：

```text
Task 里旧 targetArea = 师大
用户新输入 = 我说的是福建师范大学
```

Location clarification 重新进解析时，代码总是优先用旧 targetArea，于是新文本明明更明确，却被旧简称覆盖。

现在在澄清状态下，当前用户明确提供的新 POI 有本轮覆盖权：

```text
如果文本唯一匹配 pending candidate
→ 直接确认

如果文本是一个新的明确 POI
→ 用新名称重新查 Provider

如果仍然不确定
→ 保持 CLARIFYING
```

但注意：新名字在验证成功之前，不会直接写 durable Working Memory。

> **面试回答：**
> Location clarification 里我把“当前用户补充”提升成 request-scoped override。以前 activeCriteria.targetArea=师大会覆盖“我说的是福建师范大学”，导致重复搜索旧简称。现在优先尝试 pending candidate 唯一匹配；否则用用户新提供的明确 POI 重新 Provider 验证；只有 canonical POI 确认后才写 searchLocation。

---

**Q20：无结果以后为什么不能直接把用户条件全部放宽，继续给他推荐？**

因为那属于未经用户授权修改硬条件。

例如：

```text
用户：附近有没有兰州拉面
系统：0结果
```

如果系统直接：

```text
清掉兰州拉面
→ 推荐东北菜
```

用户会觉得系统“答非所问”。

所以系统进入一个明确的暂停状态 `WAITING_RELAXATION`，告诉用户当前条件为什么 0 结果，并提供下一步可执行选项。

后来真实对话又出现：

```text
没有兰州拉面吗？
什么意思？
我就要吃兰州拉面
无敌了，那附近有啥
```

“那附近有啥”实际上表示用户愿意放弃具体 food target。

因此定义了恢复命令 `BROADEN_FOOD_SCOPE`：只在暂停态、已有具体 food target、且用户明确泛化时生效。

它清：

```text
keyword / cuisine
```

但保留：

```text
location / GPS / radius / budget / 其他偏好 / 历史 Batch
```

> **面试回答：**
> 0 结果不能未经授权自动丢弃用户硬条件。所以我把它建模成 WAITING_RELAXATION，并定义明确 Recovery Command。比如 BROADEN_FOOD_SCOPE 只有在暂停态且用户表达“附近随便看看”这类泛化时才清 keyword/cuisine，同时保留位置、预算和其他偏好。这样 recovery 本身也是可解释、可测试的状态变化。

---

**Q21：为什么“重口吗”“环境怎么样”不能直接查 shop detail，或者让大模型根据店名回答？**

因为静态详情和主观体验是不同类型的事实。

```text
地址 / 人均 / 营业时间
→ 静态事实

重口 / 辣不辣 / 服务 / 环境 / 排队
→ 需要 Review Evidence 支撑
```

如果模型自己回答“这家应该比较清淡”，就是事实幻觉。

所以系统先判断当前问题属于：

```text
STATIC_DETAIL
EVIDENCE
VOUCHER
```

主观体验统一走 `search_shop_evidence`。

如果评价里根本没提“重口”，系统返回：

```text
topicMatched=false
```

可以给一般评价，但必须明确“现有证据不足以判断”。

> **面试回答：**
> 我把商户事实分成静态详情和 evidence-backed subjective fact。地址、人均可以查 detail，但“重口、环境、服务”必须走 Review Evidence Tool。模型只负责判断问题类型，事实本身由 Tool 提供；没有 topic-specific evidence 就明确说不确定，不能让 LLM 补一个看起来合理的结论。

---

**Q22：你的检索链路到底是什么？是不是“所有硬条件都下推 MySQL，然后 Milvus 召回”？**

这里一定要按当前真实代码讲，不能吹错。

当前真实链路是：

```text
1. province/city/district/excludeShopIds
   → MySQL SQL 粗筛 Shop

2. 加载 AiShopProfile

3. Java matchesHardConstraints()
   → 继续检查预算、菜系、距离/营业等 hard constraints

4. 得到 hardMatched shops

5. Milvus 只在 hardMatched candidate universe 内做语义评分

6. Java deterministic rerank
   → 结合语义分、星级、距离、人均偏离等

7. TopN + evidence 组装
```

所以正确口径是：

```text
行政 scope SQL 粗筛
+ Java 硬约束过滤
+ Milvus 受约束语义召回
+ 确定性重排
```

而不是“所有 hard filter 都 SQL 下推”。

> **面试回答：**
> 我的检索不是纯向量。结构化 hard constraints 必须先限制 candidate universe，当前实现是行政 scope 先 SQL 粗筛，然后 Java `matchesHardConstraints` 再做预算、菜系等过滤；Milvus 只对 hardMatched 候选做 Profile/Review 语义评分；最后再做 deterministic rerank。这样 semantic similarity 不能把已经违反硬条件的商户重新带回来。

**可能追问：为什么不纯向量？** 预算、行政范围、营业等属于业务合法性，不是相似度问题。

---

**Q23：Embedding、Milvus、Rerank 分别解决什么？**

可以用一句很朴素的话理解：

```text
Embedding
= 把自然语言变成可计算的向量

Milvus
= 在大量向量里快速找语义接近的内容

Rerank
= 语义相似只是一个信号，最后还要按业务目标重新排序
```

在这个项目里，Milvus 主要存商户 Profile 和 Review Evidence。

最终不能只按 vector score 排，因为用户真正关心的还包括：

```text
距离
预算
星级
证据覆盖
```

当前有 `semanticMinScore=0.35`、`TopK=80`、`semanticWeight=18` 等配置，但这些不能说成“实验得到的最优参数”。它们仍然是需要更多 Recall@K、MRR、证据命中率和延迟实验验证的单点值。

> **面试回答：**
> Embedding 用于把 query 和 Profile/Review 映射到语义空间，Milvus 做 ANN 召回，Rerank 再把 semanticScore 和距离、星级、预算偏离等业务信号合起来。语义分不是最终答案，只是排序的一部分。当前参数做过检索实验，但我不会包装成已经全局最优，后续还应联合 Recall@K、MRR、证据命中率和延迟调参。

---

**Q24：商户或评价变化以后，Milvus 向量怎么增量更新？为什么不直接业务事务里写 Milvus？**

如果每次数据变更都同步调用 Milvus，会遇到：

```text
MySQL 事务成功
Milvus 网络失败
```

或者反过来。

两边没有一个本地 ACID 事务。

所以项目引入 durable Vector Sync Task。

核心思路：

```text
每个向量文档有稳定 documentId

Profile: shop-profile-{shopId}
Review:  shop-review-{reviewId}

业务数据变更
→ 只把“这个文档最终应该是什么状态”写入 MySQL task 表
→ operation=UPSERT/DELETE
→ targetRevision
→ Scheduler 扫描 due task
→ Worker claim lease
→ 真正调用 Milvus
→ 成功 mark SYNCED
→ 失败指数退避重试
→ lease 过期可恢复
```

如果同一个文档连续更新多次，task 保存 latest desired state，旧 worker 不应该覆盖新 revision。

> **面试回答：**
> 我没有把 Milvus 网络写直接绑在业务事务里，而是用 durable Vector Sync Task 做最终一致性。业务事务只持久化 documentId、operation 和 targetRevision；后台 Worker claim lease 后写 Milvus，失败退避重试，lease 过期可恢复。同一 documentId 合并 latest desired state，可以避免旧任务覆盖更新版本。这样把数据库事务和外部向量库网络 IO 解耦。

---

**Q25：这个项目为什么可以叫 Agent？它和普通 ChatBot 到底差在哪？**

牛客近期 AI Agent / Java 后端+AI 面经非常常从这个问题开始。

不要只背“Agent = 感知、规划、行动”。直接结合项目说。

普通 ChatBot 可以只是：

```text
用户输入
→ LLM
→ 文本回复
```

这个项目则需要：

```text
维护状态
理解当前任务
Ground 引用和地点
决定下一动作是否合法
调用 Tool/检索
根据执行结果改变状态
失败时进入恢复流程
```

它不一定需要一个完全自由的 Planner 才叫 Agent。

> **面试回答：**
> 我认为 Agent 和普通 ChatBot 的核心差别，不在于有没有 Function Calling，而在于系统是否维护任务状态、根据状态选择动作，并通过 Tool 读取或改变外部世界。我的项目里 LLM 负责语义提议，Working Memory 保存任务状态，Resolver 做实体确认，FSM/Policy 控制动作，Tool 获取地图和商户事实，执行结果还会影响下一轮状态，所以它不是单纯的 prompt-response ChatBot。

---

**Q26：为什么不把所有事情都交给 LLM 自由规划？这样不是更“Agent”吗？**

这个项目里的问题分两类：

第一类是开放语义：

```text
“适合聊天”是什么意思？
“日本料理那个”指谁？
“那附近有啥”是在放宽还是闲聊？
```

这类适合 LLM。

第二类是确定业务规则：

```text
当前预算是多少？
哪个 Task 激活？
第二家到底是谁？
旧 version 的 Tool Result 能不能继续用？
某个 command 当前状态下是否合法？
```

这类如果全交给 LLM，就是把确定问题变成概率问题。

> **面试回答：**
> 我没有追求“LLM 控制一切”。我的原则是 LLM 做 semantic proposal，Java 做 authority decision。开放语义让模型理解，但当前状态、引用 identity、command legality、Tool 参数来源这些都用确定性代码约束。对于餐饮决策这种业务边界明确的场景，这比完全自由 Planner 更稳定，也更容易测试和解释。

---

**Q27：如果面试官问“你的 Agent 怎么降低幻觉”，应该怎么回答？**

不要只说 Prompt。

可以分三类：

第一类，状态幻觉：

```text
模型不能直接修改 canonical Working Memory
→ Turn Semantics + Reducer + FSM + OCC
```

第二类，实体幻觉：

```text
行政区 / POI / 商户 reference
→ Resolver / Provider / RecommendationBatch Grounding
```

第三类，事实幻觉：

```text
地址、人均、评价、口味
→ Tool / DB / Review Evidence
```

> **面试回答：**
> 我的幻觉治理不是只靠 System Prompt，而是尽量把模型从最终 authority 位置拿掉。状态不能由 LLM 直接写，实体必须 Resolver/Provider 验证，外部事实必须 Tool Grounding。Prompt 负责引导，Contract 负责兜底。这样即使模型提出错误候选，也不能直接变成 canonical state 或外部事实。

---

**Q28：SSE 为什么适合这个项目？为什么不用 WebSocket？**

这个项目主要需要服务端持续把处理过程和最终回答推给前端：

```text
status
text_delta
ui_component
suggested_chips
complete
```

它是典型的“客户端发一次请求，服务端持续单向输出”。

SSE 刚好适合。

当前 `POST /ai/chat/messages/stream` 用 Spring `SseEmitter`，但不会另写第二套 Agent 逻辑，而是复用同一个 ChatOrchestrationService。

异步线程里还要恢复 UserHolder，否则同步接口有登录态，SSE 线程却丢用户上下文。

Controller 设置：

```text
X-Accel-Buffering: no
Cache-Control: no-cache
```

避免 Nginx 把流式数据缓冲到最后一次性返回。

> **面试回答：**
> 这个场景主要是服务端单向流式推送，没有高频双向控制，所以 SSE 比 WebSocket 简单。项目用 SseEmitter 输出 status/text_delta/UI component/complete，而且流式入口复用原 ChatOrchestrationService，只在异步线程恢复 UserHolder，避免形成第二套状态和权限逻辑。Nginx 侧通过 `X-Accel-Buffering: no` 防止代理缓冲。

---

**Q29：Agent 调 Tool 时，怎么防止模型乱传参数、调错工具、Tool 超时？**

Function Calling 不是“模型说调用就直接调用”。

正确流程应该是：

```text
模型提议 toolName + arguments
→ schema 校验
→ identity / 状态 / 权限校验
→ 执行 Tool
→ 结果作为受信事实回到编排层
```

比如：

```text
shopId
```

不应该让模型凭空生成，而应该来自已经 Ground 好的 `ResolvedShopReference`。

POI 经纬度也应该来自地图 Provider，而不是 LLM 编一个。

对于超时：

```text
读 Tool → 有限 retry / timeout / fallback
写 Tool → 必须先有幂等和副作用边界
```

当前餐饮工具主要是读，所以重点在 timeout、降级和证据不足时停止，不涉及复杂支付 Saga。

> **面试回答：**
> Function Call 在我这里是模型提议，不是执行权限。参数先过 schema 和业务状态校验，identity 参数尽量由系统注入而不是让模型生成；Tool 超时按关键程度做有限重试或降级；如果事实拿不到，就明确失败/澄清，不能让模型用空结果补答案。对于未来有副作用的 Tool，还必须加幂等 key 和重试安全设计。

---

**Q30：为什么没有用 LangGraph / LangChain 把整个 Agent 重写？**

框架不是越多越高级。

当前系统复杂点主要在：

```text
业务状态不变量
引用 identity
Location authority
command legality
并发一致性
```

而不是“任意图编排”。

LangGraph 的 thread/checkpoint 思路和 versioned state 有相似处，但这个项目没有大量跨小时 suspend/resume、人工审批、任意 graph workflow。

如果为了用框架把稳定 Java 主链重写一遍，收益不大。

> **面试回答：**
> 我不是排斥 LangGraph，而是当前项目复杂点主要是业务 invariant，不是 graph execution。主链理解→Grounding→Reduce→Policy→Execute 很确定，所以我保留 Spring Boot 的显式 Pipeline。若未来出现长时任务、人工审批、跨小时 durable workflow，我才会考虑引入 LangGraph 类 runtime。

---

**Q31：为什么不是 Multi-Agent？把地点、推荐、评价各拆一个 Agent 不好吗？**

如果只是为了“看起来更 Agent”拆成：

```text
地点 Agent
推荐 Agent
评价 Agent
```

会引入新的问题：

```text
多个 Agent 如何共享当前状态？
哪个 Agent 有最终写权限？
上下文怎么传？
冲突怎么办？
成本和延迟怎么办？
```

当前这些职责用普通模块已经能清楚分工，没有真实的独立协作收益。

> **面试回答：**
> 我没有做 Multi-Agent，因为当前职责虽然多，但共享同一套业务状态，拆多个 Agent 反而会把单体状态一致性问题升级成跨 Agent 一致性问题。只有未来出现真正独立、可并行、工具和权限边界清楚的长任务，我才会考虑 Multi-Agent。

---

**Q32：如果面试官问“RAG 的完整流程是什么”，怎么回答才不会和自己的项目混在一起？**

先回答通用 RAG：

```text
数据采集
→ 清洗/切分
→ Embedding
→ Vector Index
→ Query Understanding
→ Retrieval
→ Rerank
→ Context Assembly
→ LLM Generation
→ Evaluation
```

然后马上补一句：

```text
我的项目不是典型 PDF 知识库 RAG
```

我的场景是商户 Profile + Review Evidence，所以主链更像：

```text
结构化硬约束缩小候选
→ Milvus 语义评分
→ deterministic rerank
→ evidence 组装
→ 生成回答
```

> **面试回答：**
> 通用 RAG 是数据清洗切分、Embedding、建索引、Query Understanding、Retrieval、Rerank、Context Assembly、Generation 和 Evaluation。我的项目不是文档知识库 RAG，而是商户 Profile/Review 检索，所以先做结构化 hard filter，再在合法候选内做 Milvus semantic recall，最后结合业务信号重排并组装 evidence。

---

**Q33：如果 RAG/语义召回效果不好，你会从哪些方向排查？**

不要第一反应就是“调 TopK”。

先看错误在哪一层：

```text
候选本来就被硬过滤掉了
→ 先查 filter / metadata

query 没表达用户真正语义
→ 查 query rewrite / semantic query

向量相似搜不到
→ 查 embedding / index / threshold / TopK

召回很多但排序乱
→ 查 rerank

有相关文档但回答仍胡说
→ 查 context assembly / evidence grounding / generation
```

> **面试回答：**
> 我会先做 error decomposition，而不是直接调 TopK。先判断问题出在 hard filter、query、vector recall、rerank 还是 generation。如果正确商户根本没进入 candidate universe，调 Milvus 没意义；如果召回有了但最终顺序差，再看 rerank；如果 evidence 已经正确但回答错，再查 generation。

---

**Q34：什么是 Rerank？为什么已经有向量相似度还要再排一次？**

向量相似度只回答：

```text
语义上像不像
```

但消费决策还关心：

```text
距离近不近
预算合不合
星级怎么样
证据够不够
```

所以向量召回通常是“先找一批可能相关的”，Rerank 再按最终业务目标排序。

> **面试回答：**
> ANN retrieval 更适合高召回地找相关候选，但 vector score 不是最终业务目标。我项目里 semanticScore 只是 rerank 的一个信号，还会结合距离、星级、预算偏离等因素。通用知识库也可以用 Cross-Encoder/LLM Reranker，但我的候选规模和业务结构比较强，所以主要用确定性业务重排。

---

**Q35：上下文太长、Token 太多怎么办？**

这个项目已经在结构上尽量避免“把所有东西都塞 Prompt”：

```text
当前业务状态 → Working Memory
历史推荐身份 → RecommendationBatch
历史执行事实 → DecisionSession
真正需要时 → 按引用去取
```

这比把完整 History + 全量 Tool Result 每轮重复发送要省很多。

一般还能做：

```text
窗口裁剪
按需 retrieval
历史摘要
减少重复 Tool schema
减少大段日志进入模型
```

但必须注意：

```text
摘要可以压语言历史
不能代替 canonical business state
```

> **面试回答：**
> 我优先从“不要重复喂不必要信息”入手。当前状态结构化放 WM，历史 identity 放 Batch，历史 execution fact 放 DecisionSession，需要时再按引用读取。Chat History 可以做窗口和摘要，但摘要不能替代 canonical state。这样上下文管理和状态管理是两件事，不混在一起。

---

**Q36：Agent 系统怎么做监控和可观测性？**

牛客近期面经也很常问。

至少分几层：

```text
模型层
→ 调用次数、成功率、Token、延迟

Tool 层
→ toolName、参数摘要、耗时、失败原因

决策层
→ Route、State Transition、Recovery、Fallback

状态层
→ WM version、OCC conflict、stale result

质量层
→ Route / Tool / Final / Locality / Recall / MRR / Evidence Coverage
```

项目里有 ConversationEvent、DecisionMetric、Evaluation Run/Diagnostics，不只是看 HTTP 200。

> **面试回答：**
> Agent 可观测性不能只看接口成功率。我会同时看模型调用、Tool 调用、状态迁移、Working Memory version 和离线质量指标。项目里 ConversationEvent 用于 trace/audit，DecisionMetric 记录决策指标，Conversation Evaluation 再按多轮 trajectory 检查 Route、Tool、Final、Locality 等 Contract。

---

**Q37：模型怎么选？是不是越强越好？**

不同任务要求不一样：

```text
结构化抽取/路由
→ JSON 稳定、低延迟、低成本更重要

复杂开放生成
→ 推理能力更重要

Embedding
→ 召回质量、维度、吞吐、成本
```

Agent 的成本也不是只看模型单价，还要看：

```text
每轮调用几次
上下文有多长
是否频繁 Tool retry
错误恢复会不会重复调用
```

> **面试回答：**
> 我不会简单按排行榜选模型。结构化抽取更看重稳定 JSON、延迟和成本，复杂生成再看能力；Embedding 则要结合 Recall 和吞吐评估。Agent 总成本是模型单价 × 调用次数 × Token，再加 Tool 重试和失败恢复，所以系统架构也会直接影响模型成本。

---

**Q38：Agent 的评测怎么做？为什么不能只看最终回答？**

一个多轮 Agent 即使最后回答看起来正常，中间也可能已经错了：

```text
Route 错了但碰巧回答对
Tool 调错但模型自己补对了
Working Memory 被污染但这轮没暴露
引用错店但店名相近
```

所以项目评测不是只看最后文本，而是沿整个多轮 trajectory 检查：

```text
Route
Tool
Final Status
Locality
Working Memory projection
Context Rewrite
历史引用
Task 切换
unseen recommendation 等
```

测试层次：

```text
Unit / targeted
→ 局部不变量

HTTP directed smoke
→ 真实 wiring

Robustness
→ 多轮边界

Conversation-v1
→ 主功能

Holdout
→ 防止只拟合固定 Dataset

人工真实口语 smoke
→ 简称、省略、前端真实表达
```

> **面试回答：**
> 我把 Agent Evaluation 当 trajectory evaluation，而不是只评最终文本。一个 Case 会同时检查 Route、Tool、Final、Locality、WM projection 等 Contract。这样能区分“最终说对了但过程错了”和真正稳定的执行。最终还保留 Holdout，不允许为了提分随便改 Ground Truth。

---

**Q39：最终评测数据到底怎么样？为什么 Complete 看起来不高？**

最终归档：

```text
Unit Tests
402 tests
0 failures
0 errors
3 skipped
```

Robustness Run144：

```text
Cases:      48
Complete:   21/48
Route:      44/48
Tool:       47/48
Final:      37/48
Locality:   48/48
Model Failures: 0
```

Conversation-v1 Run145：

```text
Cases:      40
Complete:   28/40
Route:      37/40
Tool:       32/40
Final:      40/40
Locality:   40/40
Model Failures: 0
```

Holdout Run146：

```text
Cases:      16
Complete:   7/16
Route:      13/16
Tool:       14/16
Final:      12/16
Locality:   16/16
Model Failures: 0
```

Complete 不是简单 accuracy，而是多个 Contract 的 AND。

例如：

```text
Route 对
Tool 对
Final 对
WM 对
但 unseenRecommendations 断言失败
→ Complete 仍然 false
```

所以必须拆指标看。

相对上一归档：

```text
Robustness：Complete +1 / Route +1 / Final +1
Conversation-v1：仅确认 1 条 ambiguous probe 新回归
Holdout：功能指标完全持平
```

> **面试回答：**
> 最终 402 个单测全绿。Robustness 相比上版小幅提升，Holdout 完全持平，Conversation-v1 只有一条 ambiguous probe 新回归。Complete 不是单一准确率，而是多个 trajectory contract 的 AND，所以我不会用“21/48”直接代表系统可用率，而是拆 Route、Tool、Final、Locality 和失败聚类看。

---

**Q40：既然还有这么多失败 Case，为什么你不继续修到 100%？**

这是现在这套项目最值得讲的工程判断之一。

后期已经明显出现：

```text
修一个 Case
→ 加一个局部规则
→ 新 Case 击穿
→ 再加规则
```

Location 就是最好例子：

```text
“师大”噪声多
→ UNIVERSITY hard filter

“福建师范大学附属小学”
→ 马上击穿
```

如果继续补：

```text
PRIMARY_SCHOOL
MIDDLE_SCHOOL
HOSPITAL
MALL
...
```

项目就会从“解决通用实体解析问题”变成“针对评测集维护词表”。

最终停止标准是：

```text
核心单测全绿
Robustness 没有系统性回归且有小幅提升
Holdout 不退化
新增回归很小且能定位
没有模型调用异常
```

> **面试回答：**
> 我后期没有把 100% Complete 当目标，因为已经观察到 case chasing。比如为了“师大”做 UNIVERSITY hard filter，马上被“福建师范大学附属小学”击穿。如果继续补 taxonomy，就是让生产代码拟合 Dataset。最终 402 个单测通过，Robustness 小幅提升，Holdout 不退化，只有一条 ambiguous probe 新回归，所以我选择记录边界并封档。Evaluation 的目标是发现系统性缺陷，不是诱导代码过拟合测试集。

---

**Q41：这个项目里哪些代码是 AI 辅助写的？面试官怀疑“都是 AI 写的”怎么办？**

现在牛客很多 Agent/后端面经都会追项目 ownership，这个问题必须诚实。

正确方向不是假装“所有代码都是自己逐字符写”，而是说明自己的判断力在哪。

这个项目里可以很具体地讲：

```text
AI 可以辅助：
代码检索
初版实现
补测试
重构建议

你必须负责：
真实问题定义
状态不变量
方案取舍
反例设计
代码审查
决定什么时候撤回方案
评测验收
```

例如 UNIVERSITY hard filter 就是一个非常适合证明 ownership 的例子：

```text
方案看起来合理
→ 用“福建师范大学附属小学”反例击穿
→ 没继续加 taxonomy
→ 主动回撤到名称优先、type 辅助
```

> **面试回答：**
> 项目开发过程中我大量使用过 Codex/Claude Code 辅助检索、实现和测试，但我不会把 AI 输出当完成标准。我主要负责定义问题、设计状态不变量、审查方案、构造 Bad Case、决定哪些方案应该撤回，以及通过 targeted test、HTTP smoke、Robustness/Conversation/Holdout 验收。比如 Location 的 UNIVERSITY hard filter 我最后主动撤掉，就是因为它会被真实反例击穿。面试时我可以从状态变化和代码职责解释每个关键设计。

---

## 牛客近期高频补充：这些题不用再去另外找资料

下面这些方向来自 2026 年近期公开 Java 后端 + AI Agent / AI 应用面经里反复出现的问题：Agent vs ChatBot、Memory、RAG、Rerank、Function Calling、幻觉、Tool 超时/重试、SSE、监控、上下文、ReAct/Plan、LangGraph、多 Agent、性能瓶颈和项目真实性。前面已经用项目回答过大部分，这里补几个容易被单独问的。

**Q42：Memory 有哪些类型？你的项目分别放在哪里？**

不要机械背“短期/长期”。先按用途分：

```text
语言历史
→ Chat History

当前业务状态
→ Working Memory

历史执行事实
→ DecisionSession

历史推荐身份/顺序
→ RecommendationBatch

长期业务知识
→ 商户 Profile / Review / Milvus
```

> **面试回答：**
> 我不会把所有东西都叫 Memory。Chat History 是语言上下文，Working Memory 是当前业务状态，DecisionSession 是历史执行事实，RecommendationBatch 保存历史推荐 identity，Milvus 里的 Profile/Review 属于长期业务知识。这样不同 Memory 的生命周期和 authority 更清楚。

---

**Q43：ReAct 和 Plan-Execute 有什么区别？你的项目属于哪种？**

ReAct 更像：

```text
想一步
→ 调一个 Tool
→ 看结果
→ 再想下一步
```

Plan-Execute 更像：

```text
先形成一个较完整计划
→ 再按计划执行
```

这个项目并不是完整自由 ReAct，也不是开放式长 Plan。

更接近：

```text
一次结构化语义理解
→ Java deterministic orchestration
→ 必要 Tool 执行
→ 状态机恢复
```

> **面试回答：**
> ReAct 适合任务路径需要根据 Tool Observation 动态变化的开放场景，Plan-Execute 更适合可提前分解的长任务。我的餐饮决策主链业务约束很强，所以没有完全采用自由 ReAct，而是让模型做语义提议，Java Pipeline/FSM 决定执行路径。这样更容易控制状态和 Tool 权限。

---

**Q44：怎么判断 Agent 的性能瓶颈来自模型、检索还是 Tool？**

先把总延迟拆阶段：

```text
Turn Understanding / LLM
Retrieval
Rerank
Tool
Generation
DB / Network
```

然后结合：

```text
P50/P95
Model call latency
Tool duration
Milvus retrieval duration
DB query duration
调用次数
失败重试次数
```

> **面试回答：**
> 我不会只看端到端耗时。Agent 延迟必须按模型、检索、Tool、DB、重排拆阶段，再看每层 P50/P95 和调用次数。如果模型单次不慢但一轮调了 5 次，问题是 orchestration；如果 Tool P95 高，就查 Provider；如果 retrieval 慢，再查候选规模、Milvus 和 hard filter。

---

**Q45：LLM 服务能不能缓存？**

可以，但要看输入是否真正可复用。

更适合缓存：

```text
Embedding
稳定静态生成
与用户状态无关的固定结果
```

不适合直接按 query 字符串缓存：

```text
当前 WM 不同
位置不同
时间不同
用户权限不同
```

> **面试回答：**
> LLM 可以缓存，但 key 必须包含真正影响结果的上下文。Embedding 和稳定静态任务更适合缓存；而 Agent 决策依赖 Working Memory、Location、时间和用户上下文，不能简单按 query 文本缓存，否则很容易返回旧状态答案。

---

**Q46：Prompt Engineering 在这个项目里做了什么？为什么后来不继续靠 Prompt 修？**

Prompt 主要负责：

```text
限制结构化输出
说明 Tool 能力
明确可用上下文
给模型语义边界
```

但后期很多错误不是 Prompt 能可靠解决的：

```text
Mention ≠ Mutation
行政身份必须 Provider 验证
shopId 不能模型乱造
旧 Tool Result 不能覆盖新 State
```

这些属于系统 Contract。

> **面试回答：**
> Prompt 在项目里主要做结构化输出和能力边界引导，但关键业务 invariant 后来都尽量下沉到代码。因为 Prompt 可以降低错误概率，却不能提供确定的状态写权限、版本控制和实体 authority。我的原则是 Prompt 负责引导，Contract 负责兜底。

---

**Q47：如果让你从零设计一个 Agent，你现在会先分哪些模块？**

可以从问题出发，而不是背框架组件：

```text
谁在说话、属于哪个会话？
→ Session/Input

用户这一轮想干什么？
→ Turn Understanding

系统现在记住什么？
→ State/Memory

“这个/那里/第二家”到底是谁？
→ Resolver/Grounding

当前动作允不允许？
→ Policy/FSM

怎么拿外部事实？
→ Tool Registry/Execution

失败怎么恢复？
→ Recovery/Retry

怎么知道线上到底发生了什么？
→ Trace/Observability

怎么知道系统真的变好了？
→ Evaluation
```

> **面试回答：**
> 如果从零做，我不会先问“用 LangGraph 还是 LangChain”，而是先把 Session、Turn Understanding、State、Grounding、Policy、Tool、Recovery、Observability、Evaluation 这些职责定清楚。框架只是实现手段，先确定谁拥有哪种 authority 更重要。

---

## 最后速记：面试前 10 分钟只看这里

```text
1. 项目核心不是推荐餐厅，而是多轮决策状态一致性。

2. History 记“发生过什么”；Working Memory 记“现在是什么”。

3. Flat WM 不够，因为用户会同时维护多套需求 → Task。

4. candidatePool 不够，因为“最开始第二家”需要历史批次和顺序 → RecommendationBatch。

5. “第一家太贵了，第二家有插座吗”说明一轮可能多个动作 → TurnPlan。

6. “这个日料咋样”说明 Mention ≠ Mutation，Extractor 没有状态写权限 → Turn Semantics。

7. 一个 Turn 只能有一份 WM snapshot；跨 Turn 用 OCC；慢 Tool 用 stale-result guard。

8. DecisionSession 是历史执行事实，Working Memory 是当前真相，不能互相全量覆盖。

9. Location 不能把 GPS、行政区、POI 混成一个字符串。

10. Explicit Named Location > GPS；“福州大学”不能 substring 成福州市。

11. 高德 Search 只是 Retrieval，不是 Resolution；“师大”不能拿第一条结果就执行。

12. UNIVERSITY hard filter 被“福建师范大学附属小学”击穿，所以 type 只能辅助，不能硬 gate。

13. 用户明确“我说的是福建师范大学”时，新文本覆盖旧简称，但 Provider 验证成功后才写 searchLocation。

14. 0 结果不能未经授权乱清条件 → WAITING_RELAXATION + Recovery Command。

15. “重口吗”必须 Review Evidence Grounding，没证据就说不确定。

16. 当前真实检索：行政 SQL 粗筛 + Java hard filter + Milvus semantic recall + deterministic rerank。

17. 向量增量更新：durable sync task + stable documentId + revision + lease + retry。

18. SSE 适合服务端单向流式输出，流式入口复用同一 ChatOrchestrationService。

19. Agent 幻觉治理不是只靠 Prompt：状态、实体、事实分别用代码 authority / Resolver / Tool Grounding。

20. Evaluation 看 trajectory，不只看最终文本；Holdout 用来防 Dataset 过拟合。

21. 最终 402 单测全绿；Robustness 小幅提升；Holdout 不退化；只有一条 ambiguous probe 新回归。

22. 停止开发是因为继续补已经出现 case chasing，Evaluation 用来找系统性问题，不是追求 100% Dataset。
```

最后一句项目总纲：

> **我这个项目真正的演进过程，是从“把 History 和模型当成万能解释器”，一步步发现状态、引用、地点、Tool、历史事实都需要明确 authority，最后把系统收敛成：模型负责理解，Resolver 负责实体确认，Reducer/FSM 负责状态和动作，Tool 负责外部事实，Evaluation 负责验证整个多轮轨迹。**
