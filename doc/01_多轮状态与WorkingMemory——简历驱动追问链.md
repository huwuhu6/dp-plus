# 多轮状态与 Working Memory——简历驱动追问链

## 0. 如果简历要写这一点，怎么写

建议简历 bullet：

> 设计多轮消费决策 Working Memory，将 Chat History 与当前业务状态解耦，维护预算、菜系、地点等约束及当前任务上下文；支持多轮条件增量更新、历史方案切换，并通过版本化状态与 OCC 避免并发请求覆盖。

这句话主动暴露的关键词只有：`Working Memory`、多轮条件更新、历史方案切换、OCC。它没有主动写 TurnPlan、RecommendationBatch、Location Contract 等内部名词，这些应该留到被追问时再拿出来。

---

## 1. 面试官第一问：为什么需要 Working Memory？直接把 Chat History 给模型不行吗？

### 面试官为什么会问

因为如果 Working Memory 只是“把聊天内容再存一份”，那就是重复设计。你需要先证明：History 和当前业务状态不是一种东西。

### 你真正要证明的点

Chat History 记录“发生过什么”，Working Memory 记录“系统现在认可什么”。多轮对话中存在覆盖、清除、恢复和任务切换，如果每轮都让 LLM 从整段 History 重推当前状态，就把本来应该确定的问题变成概率问题。

### 40 秒回答

> Chat History 是语言证据，它能告诉系统用户以前说过什么，但不能稳定表达哪些条件现在仍然生效。比如用户先说“福州火锅人均100”，后来把预算改到150，又切到杭州日料，再说“还是最开始那套”。如果每轮都让 LLM 从整段历史重新推断当前状态，同一段历史可能得到不同结果，而且覆盖、清除、恢复和并发版本都不好做。所以我把 History 和当前业务状态分开：History 保留语言上下文，Working Memory 保存当前 Task、criteria、searchLocation、focus 等 canonical business state。

### 面试官可能继续

- 那摘要 Memory 行不行？
- Working Memory 具体存什么？
- 为什么 Tool Result 不全塞进去？
- 这份状态放内存、Redis 还是数据库？

---

## 2. Working Memory 里面到底存什么？为什么不是越多越好？

### 面试官可能这样问

> 你说 Working Memory 是当前状态，那里面具体有哪些字段？

### 核心回答

应该存“当前业务决策需要且必须确定”的状态，例如当前 Task、预算/菜系/偏好等 criteria、searchLocation、focused shop、历史推荐批次的轻量引用、版本信息等。

不应该复制完整评论、所有 Tool Response、全部模型输出。那些是执行事实或证据，生命周期和 authority 不一样。历史执行事实更适合放 DecisionSession，历史推荐 identity/ordinal 由 RecommendationBatch 保存。

### 反驳题

> 都放一起不是更简单吗？

回答重点：短期代码可能更简单，但同一事实会在多个地方重复，后面很容易出现“当前状态已经变了，历史结果也跟着被错误解释”的 Temporal Leakage 或双真相。

---

## 3. 用户说“预算可以高一点”，Working Memory 怎么更新？

这是面试官听到“槽位/slot”后最自然的一层。

### 面试官可能问

> 这些预算、菜系、地点是谁提取的？大模型直接输出 JSON 吗？

可以回答：模型负责结构化语义提取，输出 criteria delta / slot 候选；但抽取不是最终状态写入，后面还有 mutation permission、merge/clear/inherit 等确定性逻辑。

### 面试官继续举例

```text
上一轮：人均100
这一轮：150也可以
```

预算更新到 150。

但真正难的是下面这些：

```text
“别太贵”
“换成日料”
“不要日料”
“还是最开始那套”
“这个日料怎么样”
```

这说明 Slot Extraction 本身并不是主要难点，难的是 Slot Mutation Semantics：这个字段是在修改当前状态、排除条件、切换任务，还是只是在描述某个实体。

---

## 4. 模型抽到字段以后，是不是直接写 Working Memory？

### 最重要的 Bad Case

```text
用户：这个日料怎么样？
模型：识别到 cuisine = 日料
```

如果“抽到字段 = 写状态”，当前搜索条件就被偷偷改成日料，但用户实际上只是问某家店。

### 核心结论

> 提到了一个槽位，不等于要求修改这个槽位。Mention ≠ Mutation。

当前职责应拆成三层：

```text
Turn Understanding
→ 这一轮有没有修改状态的意图

Constraint / Slot Extraction
→ 如果允许修改，具体改什么

Reducer / Merger
→ 怎么合并并写入 canonical state
```

### 30 秒回答

> 我们早期确实踩过这个坑。“这个日料怎么样”会被 Extractor 抽出 cuisine=日料，如果直接按 Delta 非空判断 mutation，就会污染当前条件。后来把语义识别和状态写权限拆开：Extractor 可以告诉系统文本里出现了“日料”，但只有 Turn Understanding 确认本轮存在条件修改意图，Delta 才有资格进入 Reducer。

### 面试官可能反驳

> Prompt 里告诉模型别乱改不就行了吗？

回答：Prompt 可以降低概率，但不能提供确定的状态写权限。业务 invariant 必须由代码兜底。

---

## 5. 为什么一份 Working Memory 后来还不够，要有 Task？

### 真实问题

```text
A：福州 / 火锅 / 100
B：杭州 / 日料 / 300
用户：还是最开始那套
```

Flat Working Memory 很容易把 B 的预算/地点和 A 的菜系重新拼在一起。继续补 merge rule 只能缓解，不能解决 representation 错误。

### 核心结论

用户可能同时维护多套独立决策需求。恢复历史方案不是重新拼字段，而是重新激活对应 Task。

### 面试官反驳

> 保存 Working Memory 历史版本不就行了吗？

回答：历史版本回答“过去某个时刻系统是什么状态”；Task 表示“当前会话里有哪些仍有业务身份的独立方案”。两者语义不同。

### 面试官继续追

- “换成日料”是新 Task 还是改当前 Task？
- 预算从100改150为什么不是新 Task？
- Task identity 谁判断？规则还是模型？

这里要诚实：当前 Task Identity 是餐饮业务 heuristic，不是通用任务管理框架。预算通常是 refinement，地点/主要 food target 的大幅切换更可能触发新 Task。

---

## 6. 用户说“最开始第二家”怎么知道是哪家？

这条通常不是简历主动写，而是 Task/历史恢复被追深后自然出现。

### 面试官会先问

> 当前 candidatePool 里不是有列表吗？直接 index=1 不行？

换一批后：

```text
第一批 A / B / C
第二批 D / E / F
用户：最开始第二家怎么样？
```

当前 candidatePool 已经只能看到 D/E/F。shownShopIds 也只知道“看过谁”，不知道 batch boundary 和 ordinal。

所以需要 RecommendationBatch 保存每轮候选顺序及当时的 decisionSessionId。

### 再追一层

> “这个日本料理怎么样”呢？

如果当前 focused 是东北菜，但历史候选里有唯一日本料理，不能因为“这个”就盲目 fallback focused。描述性 qualifier 应优先于纯 deictic fallback。

这里不用背 Resolver 名字，先把这几个 Case 讲通：

```text
第一家
上一批第一家
最开始第二家
日本料理那个
这个
```

---

## 7. 同一个请求里为什么还会出现旧状态覆盖新状态？

### 真实 Bad Case

一个请求前半段已经修改 request-scoped Working Memory，后半段某个阶段又从数据库 reload 同 version 的旧 snapshot，最终旧状态反向覆盖新状态。

### 不变量

> 一个 Turn 只能有一份 authoritative Working Memory snapshot。

Bootstrap load 一次，transition、merge、reduce、persist 都围绕同一份 request-scoped snapshot 工作。

这解决的是“单请求内部两个世界”的问题。

---

## 8. 两个请求同时改同一个会话怎么办？

### 面试官自然追问

> 同一 Turn 一份 snapshot 只能保证单请求，那两个并发请求呢？

例子：

```text
A 基于 version=20
B 基于 version=20
B 先提交 version=21
A 再提交
```

使用 OCC，append 时检查 expectedVersion；版本不匹配则拒绝旧写，不能静默覆盖新状态。

### 为什么不用悲观锁？

当前主要冲突是低频会话级状态写，OCC 可以避免长时间持锁，并且冲突可以显式失败；代价是冲突后需要决定是否重试/重新计算，不能盲目 rebase。

---

## 9. 已经有 OCC，为什么还要防慢 Tool 的旧结果？

### 面试官很可能问

> A 调 Tool 五秒，期间 B 已经把条件改了，A 的 Tool 回来怎么办？

OCC 只能阻止旧状态最终写库，但 Tool Result 本身已经是基于旧条件计算出来的。如果继续拿它更新候选池、focus 或状态，仍然会污染当前世界。

所以 runtime context 记录 baseWorkingMemoryVersion。Tool 返回后再检查当前 latestVersion；如果已经变化，则把结果标记为 stale，禁止旧 Delta 写回 canonical state。

记忆方式：

```text
same-turn snapshot
→ 单请求世界一致

OCC
→ 并发写一致

stale-result guard
→ 慢 Tool 结果一致
```

---

## 10. Location 怎么作为这条主线的深挖案例？

不要主动在简历再开一条“Location Contract”。被问到“状态更新还有什么复杂 Case”时，用下面这个就够：

```text
设备 GPS 在福州
用户明确说：北京农大附近
```

设备位置只表示用户现在在哪；显式命名地点表示用户想搜哪里。两者冲突时，Explicit Named Location 必须优先于 GPS Bias。

再进一步：

```text
用户：师大附近
```

地图搜索返回第一条并不意味着实体已经解析成功。Search 只是 Candidate Retrieval，必须经过 Candidate Resolution；未确认的 POI 不能直接成为 durable searchLocation。

这条 Case 能证明一个更一般的原则：

> Mention ≠ Verified Identity，Verified Identity 才有资格进入可执行状态。

---

## 11. 这条线最后怎么收口

面试官如果让你总结，控制在这一段：

> 这条线最开始只是想解决“多轮聊天怎么记住预算、菜系和地点”，后来真正遇到的问题是状态真值、状态写权限和并发一致性。History 只保留语言证据，Working Memory 保存 canonical state；Task 解决多套需求切换；Turn Understanding 控制谁能修改状态；同一 Turn 只保留一份 snapshot，跨请求用 OCC，慢 Tool 再用 version guard 防旧结果污染。我没有把所有语义都交给 LLM，开放语义由模型提议，最终状态 authority 保留在 Java 侧。

---

## 12. 当前不能吹什么

- Task Identity 仍是餐饮领域 heuristic，不是通用 Task Manager；
- 不能说所有状态问题都被消灭，Context Rewrite、复杂 compound semantics 等仍有边界；
- 不要把 Working Memory 讲成完整 Event Sourcing；当前主要是 versioned full snapshot；
- 不要把 Location 说成完全通用 NER/POI disambiguation 系统；当前仍依赖 Provider、名称相关度和业务规则。
