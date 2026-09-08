# AI 消费决策 Agent——面试资料阅读说明

## 当前唯一主入口

只优先阅读：

```text
AI消费决策Agent——面试主线总纲.md
```

当前 Code Truth：

```text
main b3f595f0f4d182df7c41d6ba5fc15ea3c1513c39
```

当前归档评测：

```text
Robustness Run141
Conversation-v1 Run142
Holdout Run143
```

本轮已经完成最终归档所需的：

```text
完整 mvn test
robustness
conversation-v1
holdout
人工 HTTP smoke
```

未修改 Dataset Ground Truth。

---

## 为什么资料只保留一个主入口

之前同一主线被拆成 v1/v2 和多篇 Location、Provenance、Turn Semantics 补充文档。这样复习时容易只记“最后用了什么”，却忘了：

```text
真实问题
→ 第一版判断
→ 为什么不够
→ 根因
→ 最终方案
→ 验证
→ 新边界
```

现在这些演进统一维护在 `AI消费决策Agent——面试主线总纲.md`。

旧文档只保留历史设计记录，不再作为当前面试口径。如果旧资料和总纲冲突，一律以总纲为准。

---

## 当前最值得优先复习的 5 条事故链

### 1. Working Memory 主线

```text
History 不等于当前业务真值
→ candidatePool / shownPool
→ RecommendationBatch
→ Flat WM Ghost Inheritance
→ Task Scope
→ same-turn Snapshot
→ OCC
→ stale runtime result
```

### 2. Turn Semantics 主线

```text
“这个日料咋样”被写成 cuisine
→ Mention ≠ Mutation
→ TurnCommandSet
→ 第一版仍有双 Mutation Authority
→ 删除 Extractor-based 写权限旁路
```

### 3. Location 主线

```text
GPS / Named Location 混用
→ 行政层级/身份拆分
→ Administrative Resolver
→ partial registry
→ authority leak
→ POI Mention ≠ Executable Anchor
→ “农大”需要 context-aware POI grounding
```

### 4. Reference / Fact 主线

```text
“这个日本料理重口吗”错绑 focused 东北菜
→ pure deictic 与 descriptive qualifier 混在一起
→ ReferenceIntent qualifier/deictic
→ Batch 增加轻量 cuisine/referenceTags
→ qualifier > focused fallback
→ ShopFactQueryType
→ EVIDENCE → search_shop_evidence
```

### 5. No-result Recovery 主线

```text
WAITING_RELAXATION
→ “那附近有啥”被 GENERAL_CHAT
→ 只修 route 仍会继承兰州拉面
→ BROADEN_FOOD_SCOPE
→ DecisionSession / WM 双 criteria truth
→ command whitelist projection
→ Failure Explanation single truth
```

---

## 复习方法

不要背“我们用了 Task、Resolver、TurnPlan”。每个主题都强制回答：

```text
1. 用户实际说了什么？
2. 错误表现是什么？
3. 第一版为什么不够？
4. 根因是哪两个职责/真相混在一起？
5. 最终建立了什么 authority / invariant？
6. 为什么不选更复杂方案？
7. 怎么测试？
8. full regression / holdout 说明了什么？
9. 当前还不能吹什么？
```

---

## 当前评测口径

### Run141 — robustness-v1

```text
Cases 48
Complete 20
Route 43
Tool 47
Final 36
Locality 48
```

### Run142 — conversation-v1

```text
Cases 40
Complete 29
Route 38
Tool 33
Final 40
Locality 40
```

### Run143 — holdout-v1

```text
Cases 16
Complete 7
Route 13
Tool 14
Final 12
Locality 16
```

不要把 Complete 直接理解成“只有这么多 Case 可用”。它是多个 contract 的 AND；面试时应结合 Route / Tool / Final / WM / Locality 的失败聚类解释。

也不要说所有失败都是旧 Ground Truth。当前仍存在真实债务：行政区澄清、部分 Route/Tool 语义差异、Context Rewrite 和更通用 Compound Semantics。

---

## 面试准备完成标准

至少能脱离文档连续讲清四条主线，各 5~10 分钟：

```text
1. Working Memory / Task / RecommendationBatch / OCC
2. Reference / TurnPlan / Canonical Turn Semantics
3. Location Contract / Administrative & POI Authority
4. Decision Context / No-result Recovery / Fact Tool / Evaluation
```

而且每一条都必须能讲出至少一个完整事故循环：

```text
Bad Case
→ 错误方案
→ 根因
→ 最终修复
→ 验证
→ 当前限制
```

做到这一步，资料才是面试资料，而不是项目说明书。
