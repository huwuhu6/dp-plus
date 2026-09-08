# AI 消费决策 Agent——Working Memory 主线补充：行政层级进入 Canonical State

> 这次问题表面上是“福建查不到商户”，本质上不是 SQL Bug，也不是 Task 污染，而是 **Canonical Working Memory 丢失了地点的行政层级**。前一轮我们解决了 Value / Intent / Provenance / Projection 的来源边界，这一轮进一步发现：即使来源正确，如果 Value 本身没有保留业务维度，Projection 仍然会忠实地执行一个错误状态。

## Q：这次真实问题是什么？

真实对话：

```text
Turn1：你是？
→ GENERAL_CHAT

Turn2：可以给我推荐上海的好吃的吗，我到时候准备去玩
→ START_DECISION
→ 上海当前无入库商户
→ ZERO_RESULT_NO_DATA

Turn3：好吧，福建你给我推荐一个地方呗
→ START_DECISION
→ 错误 ZERO_RESULT_NO_DATA

Turn4：真的假的？
→ EXPLAIN_SUSPENDED_DECISION
```

数据库实际上有大量福建商户：

```text
福建省 / 福州市
福建省 / 厦门市
福建省 / 泉州市
```

但旧 Canonical State 只有：

```text
targetCity
targetArea
locationIntent
```

没有省级 slot。

所以 ConstraintExtractor 面对：

```text
福建
```

只能把它塞进：

```text
targetCity = 福建
```

后面 Projection 又非常忠实地执行：

```text
request.city = 福建
```

最终 SQL 相当于：

```sql
city = '福建'
```

而真实数据是：

```text
province = 福建省
city = 福州市 / 厦门市 / 泉州市
```

所以结果自然是 0。

这里需要特别说明：

> 这次不是 Projection 擅自猜错，而是 Canonical State 本身已经错了。

这和上一轮 Location provenance Bug 恰好形成对照。

上一轮：

```text
Canonical Intent 是对的
但代码从 DecisionRequest.city 反推来源
→ Projection 被错误当成 Provenance
```

这一轮：

```text
用户来源没问题
Explicit Target 也没问题
但 Canonical Model 只有 city，没有 province
→ 行政层级在进入 Working Memory 时就已经丢失
```

所以可以概括为：

```text
上一轮：来源丢失
这一轮：层级丢失
```

---

## Q：最终怎么修？为什么不是判断“福建是不是省”？

commit：

```text
d7954b90baed39d49d2841a2cd4ddf3a6603edcc
fix: 修复行政范围建模与投影
```

最终没有在 Execution 层写：

```java
if ("福建".equals(targetCity)) ...
```

也没有根据：

```text
字符串是否以“省”结尾
```

临时判断行政层级。

原因很简单：

> Projection 不应该负责修复 Canonical State 的语义缺陷。

所以真正的修复是把 Canonical Location Model 从：

```text
targetCity
targetArea
locationIntent
```

扩展为：

```text
targetProvince
targetCity
targetArea
locationIntent
```

现在：

```text
福建
→ targetProvince = 福建省

福州
→ targetCity = 福州市

上海
→ targetCity = 上海市

鼓楼区 / 商圈 / 地标
→ targetArea
```

其中上海、北京、重庆、天津仍按 city 级搜索语义处理，而不是为了数据库字段形式把它们当成省级范围。

ConstraintExtractor 的结构化 Prompt 明确要求模型区分 province / city / area；用户只说城市时，也不会凭模型常识偷偷补其所属省份。

例如：

```text
厦门有什么推荐
```

只产生：

```text
targetCity = 厦门市
```

而不是自动把：

```text
targetProvince = 福建省
```

也写进 Canonical State。

这遵守同一个 Provenance 原则：

> 用户没说的业务事实，不因为模型“知道”就自动升级为 Canonical State。

---

## Q：为什么新增一个 targetProvince 还不够？

因为地点范围不是几个彼此独立的字符串字段，它们存在层级替换关系。

如果只增加字段但不定义 Merge 语义，很容易出现：

```text
province = 福建省
city = 上海市
```

这种 Ghost Geographic State。

所以 `ConversationCriteriaMerger` 需要定义行政 Scope Replacement。

### Province → City

```text
福建有什么推荐
→ 那厦门呢
```

结果：

```text
targetProvince = ""
targetCity = 厦门市
```

旧 province 被清除。

### City → Province

```text
上海有什么推荐
→ 那福建呢
```

结果：

```text
targetProvince = 福建省
targetCity = ""
targetArea = ""
```

这就是本次真实问题的核心回归。

### Province → Province

```text
福建
→ 广东
```

替换 province，同时清理没有在新 Delta 中重新声明的更细范围。

### City → City

```text
福州
→ 厦门
```

替换 city，并清理旧 area。

### Area refinement

```text
福州
→ 鼓楼区
```

仍然可以保留：

```text
targetCity = 福州市
targetArea = 鼓楼区
```

也就是说：

> 更细一级地点可以 refinement 当前父级；切换到另一行政范围时，要清除与新范围不兼容的旧层级。

---

## Q：行政范围加入以后，Task 模型有没有被迫重做？

没有。

这次真实轨迹：

```text
上海
→ 福建
```

`taskCount` 仍然保持 1，这在当前产品语义下是合理的。

因为 Task 的语义是：

> 用户正在考虑的独立消费方案。

只替换搜索地点，通常仍然属于同一个 broad dining demand 的 refinement，而不是自动创建另一个 Task。

但是 Task 的 `DemandSignature` 必须知道 province 的存在。

因此现在 signature 从原来的：

```text
city + area + cuisine
```

扩展为：

```text
province + city + area + cuisine
```

历史 Task 匹配时会区分省级和市级目的地。

CREATE 规则仍然保持保守：

```text
Destination 明显变化
+
Cuisine 也变化
→ 才更倾向独立 Task
```

没有因为新增 province 就改成：

```text
省一变就 CREATE
```

这是这次改动比较重要的一点：

> 扩充了 Location 的表达能力，但没有扩大 Task 状态机复杂度。

---

## Q：Province / City 变化为什么必须让 CandidatePool 失效？历史 Batch 为什么又不能删？

例如：

```text
福州推荐 A/B/C
→ 用户改成福建全省
```

旧候选 A/B/C 是在福州市范围内得到的。

新的 retrieval universe 已经变成福建全省，所以：

```text
当前 CandidatePool
```

必须失效。

现在 `targetProvince / targetCity / targetArea` 都属于 Search Domain Field，只要这些字段被 replace / clear，就会触发 Candidate Invalidation。

但是：

```text
RecommendationBatch
```

是用户历史上真实看过的结果。

所以仍然保留。

也就是说：

```text
行政范围变化
→ invalidate 当前 candidate universe

但
→ 不删除历史 RecommendationBatch
```

这和 Working Memory 主线之前建立的不变量完全一致：

> 当前可用性和历史事实不能混为一谈。

---

## Q：Province 加入以后，EXPLICIT_TARGET ↔ CURRENT_DEVICE 怎么处理？

这次必须继续遵守上一轮建立的 Scope Transition Contract。

### Explicit Province → Current Device

```text
福建有什么推荐
→ 那我附近呢
```

需要清：

```text
targetProvince
targetCity
targetArea
Task.searchLocation
当前 CandidatePool / Focus
```

然后：

```text
locationIntent = CURRENT_DEVICE
nearby = true
radius = 用户显式值，或默认3km
```

### Current Device → Explicit Province

```text
我附近找吃的
→ 还是看看福建吧
```

变成：

```text
targetProvince = 福建省
locationIntent = EXPLICIT_TARGET
```

如果用户没有重新声明 nearby/radius，则设备范围约束必须清掉：

```text
nearby = false
radius = -1
```

否则会形成：

```text
福建省
+
距离福州设备3km
```

这种语义冲突。

因此 Province 的加入没有破坏原有 provenance / intent 契约，而是把它扩展到了新的行政层级。

---

## Q：为什么省级搜索不应该再要求 GPS？

因为：

```text
福建省
```

本身已经是一个合法、确定的行政搜索范围。

它和：

```text
我附近
```

完全不同。

所以 Policy 的原则是：

```text
targetProvince / targetCity / targetArea
→ 已有 Explicit Target
→ 可以直接执行
```

设备 GPS 仍然可以保存在 Conversation Scope，但不能成为省级推荐的前置条件。

这和之前“北京已经指定为什么还让我提供位置”的问题是同一个基本原则：

> **设备位置是 Context，不是所有 Recommendation 的必选字段。**

---

## Q：这次 Execution Projection 最终长什么样？

现在 Projection 只忠实消费 Canonical State。

省级：

```text
targetProvince = 福建省
↓
request.province = 福建省
request.city = null
useLocationScope = false
```

城市级：

```text
targetCity = 福州市
↓
request.city = 福州市
useLocationScope = false
```

不会再出现：

```text
福建
→ request.city = 福建
```

也不会在 Projection 层通过字符串外观重新判断它是省还是市。

这和上一轮总结的原则连起来就是：

```text
Natural Language
↓
Canonical Value + Intent + Administrative Level
↓
Working Memory
↓
Execution Projection
↓
Retrieval
```

每一层只做自己的职责。

---

## Q：怎么证明不是针对“福建”写死？

这次新增的不是单一福建 Case，而是 9 条行政范围状态矩阵，覆盖：

```text
省级正向
不同表达方式的省级搜索
普通城市
直辖市
Province → City
City → Province
Explicit Province → Device
Device → Explicit Province
非餐饮负样本
```

没有在生产代码增加：

```text
福建 / 广东 / 浙江 ... 城市省份名单
CaseCode 特判
用户语言 contains / Regex
```

重点断言的也不是某个固定 shopId，而是：

```text
Route
Working Memory.targetProvince / targetCity
locationIntent
nearby / radius
Final Status
候选行政范围
```

定向 Run131：

```text
9/9 Case 完成
Route          9/9
Working Memory 9/9
Final Status   9/9
```

完整 robustness Run132，42 Case：

```text
Route        40/42
Tool         41/42
Final Status 40/42
```

新增行政范围 9 条全部通过。

conversation-v1 Run133：

```text
Route        38/40
Tool         33/40
Final Status 39/40
```

holdout-v1 Run134：

```text
Route        14/16
Tool         14/16
Final Status 11/16
```

所以面试时准确说法不是：

> 所有 Evaluation 都通过。

而是：

> 行政层级专项矩阵 9/9 全部通过；完整 robustness 42 条中 Route/Final 为 40/42、Tool 41/42。剩余失败仍主要集中在已有路由、Tool 和终态契约，不属于行政范围状态回归。

---

## Q：为什么新增 targetProvince 却没有升级 schemaVersion？

因为 Working Memory 使用 Jackson JSON Snapshot，而且 `DecisionConstraints` 对未知/缺失字段本身是向后兼容的。

旧 Snapshot：

```json
{
  "targetCity": "福州市"
}
```

读取到新 DTO 后：

```text
targetProvince = ""
targetCity = 福州市
```

即可正常工作。

这次不是：

```text
字段重命名
结构破坏
旧语义重新解释
```

而是增加一个带默认值的新 optional slot。

因此没有必要仅为了形式把：

```text
schemaVersion = 2
```

提升到 v3，再写一套没有实际转换逻辑的 Migration。

真正需要 schema migration 的情况应该是：

```text
旧数据无法被新模型无损解释
```

而不是：

```text
DTO 多了一个默认空字段
```

---

## Q：这次和上一轮 provenance Bug 放在一起，怎么讲最有价值？

可以用一个连续故事。

第一步，我发现：

```text
有 GPS
≠
按 GPS 附近搜索
```

于是修复了 State → Execution Projection 的 scope contract。

第二步，我又发现：

```text
DecisionRequest.city = 福州
```

不能证明这个 city 是用户说的还是 GPS 反向地理得到的。

于是明确：

```text
Value ≠ Intent ≠ Provenance ≠ Projection
```

第三步，再遇到：

```text
福建
```

系统知道这是用户显式目的地，也知道它不是 GPS，但仍然失败。

原因是：

```text
Canonical Value 只有一个 location string / city slot
```

没有保存行政层级。

所以进一步得到：

```text
Value 本身也必须保留业务维度
```

最终这一段演进可以概括成：

```text
地点字符串
↓
Explicit / Current Device Intent
↓
Value / Intent / Provenance / Projection 分离
↓
Province / City / Area 行政层级进入 Canonical State
```

这比单纯说：

> 我给 Working Memory 加了 targetProvince。

要有价值得多。

真正的工程结论是：

> **结构化 Working Memory 的价值不只是“把聊天文本变成 JSON”，而是要保存后续业务决策真正依赖的语义维度。只保存值、不保存来源会错；只保存来源、不保存层级同样会错。**

---

## 当前还需要诚实说明的边界

目前 Location 行政模型已经能表达：

```text
province
city
area
current device
```

但它仍然不是一个完整的全国行政区知识系统。

例如：

```text
同名区县消歧
跨省同名地标
行政区划变更
完整父子级合法性校验
```

当前主要依赖结构化模型提取以及现有 Location Resolver / 数据库事实，没有引入完整权威行政区编码体系。

如果业务真的进入大规模生产，更成熟的方式会是保存类似：

```text
regionCode
regionLevel
parentRegionCode
canonicalName
source
```

并依赖高德/国家统计区划等权威 Region Directory 做解析和校验。

当前项目数据规模和业务目标下，`targetProvince / targetCity / targetArea` 是一个更低复杂度、足以解决当前真实问题的建模选择。
