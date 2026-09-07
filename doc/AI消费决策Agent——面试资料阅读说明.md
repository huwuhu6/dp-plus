# AI 消费决策 Agent——面试资料阅读说明

## 当前唯一主入口

只优先阅读：

```text
AI消费决策Agent——面试主线总纲.md
```

当前 Code Truth：

```text
main c45c71ba0a69235aaf485ee4759baae8a878f616
```

最近一次完整全量评测仍是：

```text
Robustness Run139（709c615）
Conversation-v1 Run140（709c615）
Holdout 尚未执行
```

`5a03adf → 5fdc9e3 → ec2b4b4 → c45c71b` 之后主要完成了：

```text
BUSINESS_FOLLOW_UP Mutation Authority 收敛
WAITING_RELAXATION No-result Recovery
Relaxation Command → Canonical WM Projection
Failure Explanation 单一事实源
BROADEN_FOOD_SCOPE 泛化边界
```

这些改动目前只有定向测试，尚未重新跑 full robustness / conversation-v1，因此面试时不能把 Run139/140 说成当前 `c45c71b` 的最终全量成绩。

---

## 为什么资料重新收敛

之前同一条主线被拆成：

```text
Working Memory v1/v2
Location Contract
Value / Intent / Provenance / Projection
行政层级
Decision Context / Provenance
Canonical Turn Semantics
```

这样的问题是：一个真实 Bad Case 的“现象、第一版方案、为什么失败、下一轮修复、最终边界”分散在多篇文档里，复习时必须自己跨文件拼接。

现在统一改成：

```text
AI消费决策Agent——面试主线总纲.md
```

总纲中的每条核心演进尽量使用同一结构：

```text
真实现象
→ 第一版判断 / 方案
→ 为什么失败
→ 根因
→ 备选与取舍
→ 最终方案
→ 代码职责
→ 验证
→ 新暴露的边界
→ 面试追问
```

后续 Working Memory 主线的新结论直接维护总纲，不再新增新的“主线补充：xxx.md”。

---

## 旧资料如何处理

以下文件现在只作为历史设计记录，不再作为当前面试口径：

```text
AI消费决策Agent——Working Memory主线面试资料 v1.md
AI消费决策Agent——Working Memory主线面试资料 v2.md
AI消费决策Agent——Working Memory主线补充：Canonical Turn Semantics.md
AI消费决策Agent——Working Memory主线补充：Location Contract与行政实体解析.md
AI消费决策Agent——Working Memory主线补充：Value Intent Provenance Projection.md
AI消费决策Agent——Working Memory主线补充：决策可解释性与约束溯源.md
AI消费决策Agent——Working Memory主线补充：状态投影与位置语义.md
AI消费决策Agent——Working Memory主线补充：行政层级进入Canonical State.md
```

如果旧资料与总纲冲突：

```text
统一以 面试主线总纲.md 为准。
```

这些旧文件仍可用于追溯“当时为什么这么设计”，但不要按“v2 + 六篇补充”的方式复习。

---

## 推荐复习方式

### 第一遍：只看总纲

目标不是背类名，而是能连续讲出项目演进。

至少要能手推：

```text
candidatePool → RecommendationBatch
Flat WM → Task Scope
same-turn Snapshot → OCC → stale result
Reference → TurnPlan
Location Value/Intent/Provenance/Projection → Resolver Authority
Decision Context → Provenance / Historical Fact
Canonical State → Canonical Turn Semantics
WAITING_RELAXATION → Recovery Command / Failure Explanation
```

### 第二遍：按“事故循环”复述

每个主题强制回答：

```text
1. 用户实际说了什么？
2. 系统当时错误表现是什么？
3. 第一反应为什么不够？
4. 真正根因是哪两个职责混在一起？
5. 最终引入了什么 invariant / authority？
6. 怎么验证没有只修一个 Case？
7. 新方案又暴露了什么边界？
```

如果只能说“我们增加了 Task / Resolver / TurnCommandSet”，说明还没准备好。

### 第三遍：模拟追问

重点链路：

```text
为什么不用 History？
为什么不用 LangGraph？
为什么 Full Snapshot？
为什么 OCC 还要 stale-result guard？
为什么 Mention 不能直接写 slot？
为什么行政区不能全交 LLM？
为什么 DecisionSession 不能覆盖 WM？
为什么无结果不能自动放宽？
为什么 Failure Explanation 也需要单一事实源？
怎么证明没有针对评测集过拟合？
```

---

## 面试准备完成标准

你能脱离文档，把下面四条主线各讲 5~10 分钟并接受追问：

```text
1. Working Memory / Task / RecommendationBatch / OCC
2. Reference / TurnPlan / Canonical Turn Semantics
3. Location Contract / Administrative & POI Authority
4. Decision Context / Provenance / No-result Recovery / Evaluation
```

并且每条都能讲出至少一个：

```text
真实 Bad Case
→ 错误方案
→ 根因
→ 最终修复
→ 验证结果
→ 当前限制
```

做到这一步，这份资料才真正是“面试资料”，而不是项目说明书。
