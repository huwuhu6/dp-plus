# AI 消费决策 Agent——面试资料阅读说明

## 当前主入口

优先阅读：

```text
AI消费决策Agent——Working Memory主线面试资料 v2.md
```

当前 Code Truth：

```text
main 33d37e1d494ad3e03d53ebc0ce9be0d50e7a5805
```

当前阶段全量评测口径：

```text
Robustness Run 137
Conversation-v1 Run 138
Holdout 尚未重新执行
```

`v2` 已经把以下内容统一成一条面试主线：

```text
Working Memory
Task / RecommendationBatch
Reference / TurnPlan
同 Turn Snapshot
OCC / stale runtime result
Location Contract / Administrative Authority
Decision Context Query / Provenance
Evaluation
工业方案对照
牛客高频追问
项目当前边界
```

如果只准备一次面试，先把 `v2` 吃透，再按专题补充材料查细节。

---

## 专题补充材料

### Location 深挖

```text
AI消费决策Agent——Working Memory主线补充：Location Contract与行政实体解析.md
```

适合继续追：

```text
Value / Intent / Provenance / Projection
Province / City / District / Area
partial registry
Amap District Provider
POI vs Administrative Entity
CURRENT_DEVICE / GPS
```

注意：该专题文档形成较早，部分顶部 SHA 和最新 Run 数据可能落后；涉及“当前最终实现”和最新评测时，以 `Working Memory主线面试资料 v2.md` 为准。

### Decision Context / Provenance 深挖

```text
AI消费决策Agent——Working Memory主线补充：决策可解释性与约束溯源.md
```

适合继续追：

```text
WHY_RECOMMENDED
CONSTRAINT_PROVENANCE
CURRENT_CRITERIA
Historical Decision Fact
RecommendationBatch pointer
AiDecisionSession.resultJson
```

最新 `EXECUTED_SEARCH_SCOPE` 及后续行政范围查询演进，以 `v2` 为准。

### Value / Intent / Provenance / Projection

```text
AI消费决策Agent——Working Memory主线补充：Value Intent Provenance Projection.md
AI消费决策Agent——Working Memory主线补充：状态投影与位置语义.md
AI消费决策Agent——Working Memory主线补充：行政层级进入Canonical State.md
```

这些文档主要保留设计演进过程，适合复盘“为什么会从一个字段逐渐拆成多个语义维度”。

---

## 历史资料

```text
AI消费决策Agent——Working Memory主线面试资料 v1.md
```

保留用于查看早期 Working Memory / Task Scope 演进，不再作为当前面试主口径。

如果 v1 与 v2 在以下内容出现差异：

```text
当前 Code Truth
Location Authority
Decision Context Query
最新评测数字
当前已知限制
```

统一以 v2 为准。

---

## 推荐复习顺序

### 第一遍：只看 v2

目标：能完整讲出：

```text
为什么做
出了什么问题
为什么这样改
不用其他方案的原因
怎么验证
还差什么
```

### 第二遍：手推两个核心 Case

```text
A → B → A
```

和：

```text
第一家太贵了，第二家有插座吗？
```

要求能画出：

```text
Task
RecommendationBatch
Reference
Criteria Delta
Candidate Invalidation
TurnPlan
```

的时序。

### 第三遍：Location 深挖

重点不是背行政区名称，而是记住：

```text
Value ≠ Intent ≠ Provenance ≠ Projection
Administrative Level ≠ Identity
String Mention ≠ Entity Mention
Partial Dataset ≠ Complete World
Named Location ≠ Current Device
```

### 第四遍：Evaluation

必须能准确解释：

```text
为什么不能只看 Complete
为什么 Route / Tool / Final / WM 要拆开
为什么 targeted regression 和 full regression 分层
为什么 Holdout 要留到后面
为什么不能为了 Ground Truth 改生产逻辑
```

---

## 面试准备完成的判断标准

不是“文档看完”，而是你能脱离文档回答：

1. 30 秒和 2 分钟项目介绍；
2. Chat History / Context / Working Memory 区别；
3. candidatePool / shownPool / RecommendationBatch 区别；
4. Flat WM 为什么会 Ghost Inheritance；
5. same-turn Snapshot 和 OCC 为什么不重复；
6. slow Tool stale result 怎么防；
7. Reference 为什么必须 pre-mutation resolve；
8. TurnPlan 为什么拆 CriteriaIntent / ExecutionAction；
9. 为什么行政区不能完全交 LLM；
10. 为什么“福州大学”不能因为 contains “福州”就变成福州市；
11. 为什么只读 Context Query 不能改 WM；
12. 为什么历史推荐解释必须读取历史 DecisionSession；
13. 最新 Run137 / Run138 怎么解释；
14. 当前最大的工程债是什么；
15. 和 LangGraph / OpenAI Agents SDK 的相同点和不同点。

这些能稳定回答后，Working Memory 主线才算真正面试可用。
