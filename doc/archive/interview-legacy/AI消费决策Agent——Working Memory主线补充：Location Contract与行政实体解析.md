# AI 消费决策 Agent——Working Memory 主线补充：Location Contract 与行政实体解析

> 本文是 Location / 行政区支线的**当前最终口径**，以 main `a47b0867c95744a16502bd2fdb843f632dd9c6a9` 为 Code Truth。它补充并更新前几轮“Location provenance”“行政层级进入 Canonical State”等复盘中的实现细节。尤其注意：当前已经将 `targetDistrict` 与 `targetArea` 分离，区县不再和商圈/地标共用一个 slot。

---

## Q1：这一整条 Location 支线最后解决的到底是什么问题？

不是单独修了“北京”“福建”“鼓楼”几个 Case，而是逐步发现：

```text
地点字符串
≠
地点业务语义
≠
地点来源
≠
行政层级
≠
行政实体 identity
≠
执行投影
```

真实问题经历了几轮演进。

### 第一轮：Projection 不完整

用户明确搜索北京，但浏览器同时上传福州 GPS；Location 恢复时只写入经纬度，没有完整投影：

```text
CURRENT_DEVICE
nearby
radius
useLocationScope
```

结果出现设备位置与命名目的地语义混用。

### 第二轮：Value 和 Provenance 混淆

`DecisionRequest.city=福州` 既可能来自：

```text
用户明确指定福州
```

也可能来自：

```text
GPS reverse geocoding
```

旧逻辑却从执行投影字段反推“用户是否显式指定地点”。

历史 Case：

```text
重庆 / EXPLICIT_TARGET
→ 用户：我附近
```

修复前仍停在：

```text
targetCity=重庆
locationIntent=EXPLICIT_TARGET
```

说明：

> Projection Value 不能反推 Intent / Provenance。

### 第三轮：行政层级丢失

用户：

```text
福建有什么推荐？
```

旧 Canonical Model 没有 province，只能产生：

```text
targetCity=福建
```

于是 SQL：

```sql
city='福建'
```

但真实商户数据是：

```text
province=福建省
city=福州市 / 厦门市 / 泉州市
```

这里不是 SQL 错，而是 Canonical State 在进入 Retrieval 前就已经丢失了行政层级。

### 第四轮：District 与 Area 混用

“闽侯县”“鼓楼区”与“福州大学”“解放碑”不能使用同一个执行维度：

```text
闽侯县 / 鼓楼区
→ district hard filter

福州大学 / 解放碑 / 东街口
→ POI / Landmark geocoding
```

因此当前模型进一步拆成：

```text
targetProvince
targetCity
targetDistrict
targetArea
```

### 第五轮：LLM fallback 导致行政语义丢失

真实聊天中：

```text
用户：帮我看看鼓楼有什么东西吃
```

ConstraintExtractor 的模型调用成功返回，但：

```text
tool_calls=[]
```

旧逻辑整体 fallback 到普通 Rule Extractor，结果行政字段全部为空。

用户随后选择“按全城搜索”，系统失去“鼓楼”这个显式地点，导致跨城市结果混入。

这个问题说明：

> Fallback 不能只保证程序不报错，还必须保证关键业务语义不发生 fail-open degradation。

### 第六轮：行政区同名 identity 与 partial registry

即使知道“鼓楼区”是 DISTRICT，也不能证明它就是福州市鼓楼区。

```text
Level = DISTRICT
≠
Identity = 福州市鼓楼区
```

裸“鼓楼”可能对应多个城市。

同时，本地 Registry 是 partial 时：

```text
本地只有一条候选
≠
全国唯一
```

所以最终行政区解析从普通 LLM Semantic Extraction 中独立出来，形成 closed-world entity resolution。

---

## Q2：当前最终 Location Contract 是什么？

当前分成四条互不替代的 authority：

```text
用户地点表达
│
├── Province / City / Municipality / District
│   → AdministrativeRegionResolver
│   → Local Administrative Registry
│   → local miss 时 Amap District WebService fallback
│
├── POI / Landmark / Business Area
│   → Amap MCP maps_geo
│
└── CURRENT_DEVICE
    → Browser GPS
```

可以直接记成：

```text
行政区 → Closed-world Entity Resolution
POI    → Geocoding
我附近 → Device Location
偏好    → LLM Semantic Extraction
```

这四类问题虽然表面上都是“地点”，但需要的 authority 完全不同。

---

## Q3：为什么行政区不能继续完全交给 LLM？

因为行政区具备典型 closed-world 特征：

```text
有限集合
固定行政层级
父子关系
标准名称
adcode
同名歧义
```

而：

```text
安静
性价比高
适合约会
想吃辣一点
```

属于 open-world semantic extraction。

LLM 很适合后者，但行政区最终 identity 不应该由模型自由生成。

因此当前职责是：

```text
ConstraintExtractor
→ cuisine / budget / preferences / keyword / relative mutation ...

AdministrativeRegionResolver
→ province / city / district identity
```

模型可以提供 hint，但 Resolver 才是行政字段 authority。

面试时可以概括：

> 我后来把“用户想吃什么”和“鼓楼到底是哪一个行政区”拆开了。前者是开放语义理解，后者是闭集实体解析，所以行政区不再依赖 LLM tool call 是否成功。

---

## Q4：当前 Canonical Location State 有哪些核心字段？

当前 DecisionConstraints 重点地点字段：

```text
targetProvince
targetCity
targetDistrict
targetArea
locationIntent
nearby
radiusKm
```

语义：

```text
targetProvince
= 省级行政范围

targetCity
= 城市 / 直辖市

targetDistrict
= 数据库 district 维度对应的县级行政范围，包括区、县

targetArea
= POI / 地标 / 商圈等非行政范围

locationIntent
= EXPLICIT_TARGET / CURRENT_DEVICE / UNSPECIFIED
```

例如：

```text
福建
→ targetProvince=福建省

福州
→ targetCity=福州市

福州鼓楼区
→ targetProvince=福建省
→ targetCity=福州市
→ targetDistrict=鼓楼区

福州大学
→ targetArea=福州大学

我附近
→ locationIntent=CURRENT_DEVICE
```

---

## Q5：为什么 targetDistrict 和 targetArea 必须拆开？

因为它们最终进入的 Execution Contract 不同。

行政区县：

```text
targetDistrict=鼓楼区
↓
request.district=鼓楼区
↓
MySQL district hard filter
```

地标：

```text
targetArea=福州大学
↓
LocationResolutionProvider
↓
Amap MCP maps_geo
↓
坐标搜索
```

如果把所有 `targetArea` 直接映射成：

```sql
district = ?
```

就会：

```text
修好闽侯县
打坏福州大学 / 解放碑
```

所以这里的重要原则是：

> 相似的自然语言形式，不代表相同的业务执行维度。

---

## Q6：为什么“行政范围越具体”不代表“越需要 GPS”？

这是整个 Location Contract 最容易被误解的点。

例如：

```text
用户：鼓楼区有什么吃的？
```

如果已经明确是某个城市下的鼓楼区，那么：

```text
city=福州市
district=鼓楼区
```

已经足以执行行政硬过滤。

根本不需要用户当前位置。

真正需要 Browser GPS 的只有：

```text
我附近
离我近一点
当前位置
周边3公里
```

即：

```text
locationIntent=CURRENT_DEVICE
```

因此当前不变量是：

> **设备定位只服务于相对用户当前位置的语义，而不是所有更细地点的默认前置条件。**

---

## Q7：为什么省、市优先走本地 Registry，而不是每次调用高德？

因为省、市、直辖市是低频变化的闭集实体。

每次：

```text
福州有什么吃的？
```

都请求地图服务，会带来：

```text
额外网络 RTT
额度消耗
外部依赖
失败面扩大
```

而本地 Registry 可以做到：

```text
低延迟
确定性
可测试
无需外部服务
```

所以：

```text
Local Registry = primary path
Amap District WebService = fallback / freshness provider
```

而不是反过来。

---

## Q8：为什么行政区 fallback 用高德 WebService，而 POI 继续使用 MCP？

因为两者解决的问题不同。

行政区需要：

```text
adcode
level
父子关系
identity
ambiguity
```

这对应高德行政区域查询 `/v3/config/district`。

POI / 地址需要：

```text
地址文本
→ 经纬度
```

当前项目已有：

```text
AmapMcpLocationResolutionService
→ maps_geo
```

所以最终不是“API 比 MCP 好”，而是：

> **哪个外部能力的 Contract 更符合当前领域问题，就用哪个。**

行政 identity 用 District WebService；POI geocoding 用 MCP。

---

## Q9：高德行政区 Provider 怎么避免成为新的强依赖？

高德只在：

```text
Local Registry NOT_FOUND
```

时作为 fallback。

正常省、市请求完全不调用远程服务。

Provider 还做了：

```text
TTL cache
HTTP timeout
5xx 安全降级
quota / malformed response 安全降级
```

失败后：

```text
NOT_FOUND / clarification
```

而不是：

```text
猜一个行政区
或
让餐饮主链路 500
```

这体现的是：

> External Provider 可以提高 coverage，但不能成为主链路可用性的单点依赖。

---

## Q10：高德接入过程中踩了什么坑？

最典型的一个坑是：一开始按照自己的 Domain Model 想当然地解析外部响应。

旧 Provider 错误读取：

```text
province
city
district
parent
```

但高德 `/v3/config/district` 节点实际核心字段是：

```text
citycode
adcode
name
center
level
districts
```

所以：

```text
外部 API 成功返回
≠
内部 Domain Model 就自动完整
```

最终改成：

```text
官方响应
→ adcode / name / level
→ 本地 Repository 按 adcode enrichment hierarchy
```

例如：

```text
350102
→ 350100
→ 350000
```

补全：

```text
福建省 / 福州市 / 鼓楼区
```

如果本地没有对应 adcode：

```text
保留已确认的 adcode/name/level
不编造 parent hierarchy
```

这个问题很适合面试，因为它说明：

> 接第三方 API 不只是“请求成功”，还必须以对方真实 schema 为边界，不能假设外部服务会返回与自己领域模型完全一致的数据。

---

## Q11：裸“鼓楼”为什么不能直接认为是福州鼓楼区？

因为行政区存在同名实体。

```text
鼓楼区
```

可能属于不同城市。

所以：

```text
DISTRICT
```

只是行政 level，不是唯一 identity。

当前 Resolver 三态：

```text
RESOLVED
AMBIGUOUS
NOT_FOUND
```

例如：

```text
福州 → 鼓楼呢？
```

因为当前 Canonical Context 已有：

```text
targetCity=福州市
```

可以限定 parent：

```text
福州市 children
→ 鼓楼区
→ RESOLVED
```

但新会话直接：

```text
鼓楼有什么吃的？
```

没有 parent：

```text
→ AMBIGUOUS
→ 请求用户明确城市
```

不会：

```text
根据 GPS 猜福州
```

也不会：

```text
因为测试数据里只有福州鼓楼商户就认定福州
```

---

## Q12：为什么 partial Registry 中只有一个候选，也不能直接认为唯一？

这是后来专门修过的一个 false uniqueness 问题。

例如 Registry 当前只收录一个：

```text
西湖区
```

只能证明：

```text
当前文件里只有一个西湖区
```

不能证明：

```text
全国只有一个西湖区
```

因此 Registry 明确带 metadata：

```text
version
completeProvinceCity
completeDistrict
```

当：

```text
completeDistrict=false
```

时，裸区县即使本地候选只有一条，也不能仅凭：

```text
xxx区
xxx县
```

后缀宣布 identity 唯一。

允许直接 RESOLVED 的主要情况是：

```text
当前句有 parent
Working Memory 有 parent
Registry completeDistrict=true
权威 Provider 返回可确认 identity
```

这条原则可以概括：

> **Dataset completeness 本身也是推理前提，不能把 partial snapshot 当成 complete world。**

---

## Q13：为什么不能用 tbl_shop 的 DISTINCT province/city/district 当行政区词典？

因为：

```text
行政区是否存在
```

和：

```text
当前业务库是否有商户覆盖
```

是两个概念。

例如成都、上海即使当前没有商户：

```text
仍然是合法行政区
```

如果行政 Registry 来源于：

```sql
SELECT DISTINCT province, city, district FROM tbl_shop
```

就会把：

```text
没有商户数据
```

误解释成：

```text
不是合法地点
```

所以当前：

```text
Administrative Registry
```

与：

```text
Business Data Coverage
```

严格分离。

---

## Q14：行政区切换时，Working Memory 怎么避免 Ghost State？

地点字段存在层级替换关系。

例如：

### Province → City

```text
福建
→ 厦门
```

要清：

```text
old targetProvince
old targetDistrict
old targetArea
```

### City → Province

```text
上海
→ 福建
```

要清：

```text
old targetCity
old targetDistrict
old targetArea
```

### City → District refinement

```text
福州
→ 鼓楼区
```

可以保留：

```text
targetCity=福州市
```

同时新增：

```text
targetDistrict=鼓楼区
```

### Explicit → Current Device

清：

```text
targetProvince
targetCity
targetDistrict
targetArea
```

进入：

```text
CURRENT_DEVICE
```

### Current Device → Explicit

清除没有被重新声明的：

```text
nearby
radiusKm
```

避免：

```text
北京 + 福州设备3km
```

这种跨 Scope Ghost State。

---

## Q15：行政范围变化为什么 CandidatePool 要失效，但 RecommendationBatch 要保留？

例如：

```text
福州 → 鼓楼区
```

检索 universe 已变化，所以当前：

```text
candidatePool
focusedShop
```

不再可靠，必须 invalidate。

但用户之前真实看过的推荐属于 Historical Fact：

```text
RecommendationBatch
```

不能因为当前范围改变就删除。

所以仍然遵守 Working Memory 主线中的核心不变量：

```text
Current Projection 可以失效
Historical Fact 不能被抹掉
```

---

## Q16：为什么“福州 → 鼓楼呢？”之前会被不同 Pipeline 路径理解不一致？

因为一度存在：

```text
START_DECISION
→ extract(message, activeCriteria)

另一条 ensureCriteriaDelta 路径
→ extract(message)
```

也就是说同一个 request-scoped Working Memory：

```text
一条路径看得到 parent city
另一条路径看不到
```

这会导致：

```text
鼓楼呢？
```

在 START_DECISION 和 BUSINESS_FOLLOW_UP 判断中产生不同结果。

后来统一成：

> 只要当前 Pipeline 已经拿到 request-scoped Working Memory，Constraint Extraction 就使用同一份 active criteria snapshot。

这和前面解决的 same-turn snapshot consistency 是同一种工程原则：

```text
一个 Turn
一个 authoritative state view
```

---

## Q17：为什么这次不把 adcode 直接持久化进 Working Memory？

当前 `adcode` 保留在：

```text
AdministrativeRegion candidate
```

而没有扩大：

```text
DecisionConstraints / Working Memory
```

原因是当前 Retrieval Contract 仍然是：

```text
province
city
district
```

本地 SQL 尚不依赖 adcode。

目前 adcode 主要用于：

```text
Resolver identity
Provider enrichment
parent hierarchy
```

所以阶段性选择是：

> 有明确业务消费者再进入 Canonical WM，不因为“以后可能有用”提前扩大 Durable State。

如果未来：

```text
商户表标准化 adcode
行政区 rename / mapping
跨数据源 identity join
```

成为真实需求，再将 adcode 升级成 canonical durable field。

---

## Q18：为什么没有做一个完整 Location State Machine？

因为现有状态已经能够表达真正需要的业务语义：

```text
EXPLICIT_TARGET
CURRENT_DEVICE
province/city/district/area
searchLocation
deviceLocation
```

这一系列 Bug 的根因主要是：

```text
authority boundary 不清
semantic dimension 不足
projection 不完整
resolver / fallback 职责混用
```

而不是缺少一个大 FSM。

所以最终选择：

```text
修 authority
补 canonical dimension
拆 resolver
定义 scope transition
```

而没有新造：

```text
LocationSaga
GeoWorkflow
复杂位置状态机
```

这体现的是最小架构修复原则：

> 如果已有状态可以表达业务，只修职责边界，不为了一个 Bug 引入新的框架层。

---

## Q19：怎么证明这些改动不是围绕几个 Case 写死？

代码层约束一直保持：

```text
无具体省市区 CaseCode 特判
无“鼓楼/闽侯”生产 contains 特判
无城市名单 if/else
无固定商户结果断言
```

行政区数据进入统一 Registry，而不是散落在业务逻辑中。

测试重点也从：

```text
某一句话 → 某一个店
```

提升成：

```text
行政 level
parent hierarchy
ambiguity
scope transition
projection
candidate invalidation
provider degradation
```

代表性定向验证包括：

```text
福建 → targetProvince
福州 → targetCity
福州鼓楼区 → province/city/district
裸鼓楼 → AMBIGUOUS
福州 → 鼓楼呢？ → parent context resolution
我附近 → CURRENT_DEVICE
福州大学 → targetArea / POI
```

高德 Provider 还单独验证了：

```text
官方 response schema
adcode hierarchy enrichment
partial registry false uniqueness
malformed response degradation
```

---

## Q20：目前这条支线有哪些可引用的验证数据？

行政范围早期专项 Run131：

```text
9/9 Case 完成
Route          9/9
Working Memory 9/9
Final Status   9/9
```

当时完整 robustness Run132：

```text
42 Cases
Route        40/42
Tool         41/42
Final Status 40/42
```

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

后续行政 Resolver / Provider 收口采用 targeted regression，没有每轮重复全量评测。

最近相关验证：

```text
AdministrativeRegionResolver / Repository / Provider
ConstraintExtractor
ChatOrchestrationService
Amap MCP Location Resolver
```

定向测试曾达到：

```text
59 tests
0 failures
0 errors
1 skipped
```

最后一次高德 parser / partial identity 校正：

```text
10 tests
0 failures
0 errors
```

注意：

> `a47b086` 后尚未重新跑完整 robustness / v1 / holdout，因此不能把 Run132/133/134 说成最新 HEAD 的完整回归结果。

面试时应准确表达：

> 行政区主线经历过完整 robustness/v1/holdout 阶段回归；最后几次 Resolver/Provider 收口采用 targeted regression，完整回归被刻意延后，避免每个小修复都重复执行高成本模型评测。

这反而可以作为 Evaluation Engineering 的一个 trade-off 来讲。

---

## Q21：这条支线最终最值得讲的几个工程原则是什么？

### 1. Value ≠ Intent ≠ Provenance ≠ Projection

同样：

```text
city=福州
```

不代表它来自用户，也不代表当前一定在搜索福州。

### 2. Administrative Level ≠ Administrative Identity

```text
鼓楼区是 DISTRICT
```

不等于：

```text
它一定是福州市鼓楼区
```

### 3. Explicit Named Location ≠ Device Location

命名地点需要 identity / parent clarification；只有“我附近”才需要 Browser GPS。

### 4. Closed-world Entity Resolution ≠ Open-world Semantic Extraction

行政区用 Registry / adcode / hierarchy；消费偏好继续用 LLM。

### 5. Projection 只能消费 Canonical State

不能在 Execution 层通过字符串外观重新猜省、市、区县。

### 6. Partial Dataset ≠ Complete World

```text
本地唯一
```

不等于：

```text
现实世界唯一
```

### 7. Fallback 也必须维护语义正确性

```text
模型失败但程序不报错
```

并不等于健壮。

如果 fallback 把显式行政范围丢掉并退化到全国检索，这是一种 semantic fail-open。

### 8. External API Contract 必须按真实 Schema 建模

不能因为内部 Domain Model 有 province/city/parent，就假设外部 API 也会直接返回这些字段。

---

## Q22：面试里怎么用 1～2 分钟把这个故事讲出来？

可以按这条主线：

> 我们 Agent 里 Location 一开始只是 ConstraintExtractor 里的几个字符串字段，但真实对话很快暴露出几个层次的问题。比如用户指定北京时，设备 GPS 也会存在，最早代码会把 Execution Request 里的 city 反过来当成用户来源，所以我先把 Value、Intent、Provenance 和 Projection 分开。随后又发现“福建”被塞到 targetCity，虽然 Projection 很忠实，但 SQL 还是会查错维度，于是 province/city/district 进入 canonical state。
>
> 后面真实聊天又出现模型 tool call 为空，“鼓楼”行政语义在 fallback 里整个丢掉。我发现行政区其实不应该依赖 LLM，它是一个 closed-world entity resolution 问题，所以把它独立成 AdministrativeRegionResolver：本地 Registry 是主路径，高德 District API 只做 miss/freshness fallback；POI 仍然走高德 MCP geocoding，“我附近”才走 Browser GPS。
>
> 最后还处理了同名行政区和 partial registry 的 false uniqueness：知道“鼓楼区”是 district 不代表知道它是哪一个鼓楼，本地文件只有一条也不代表全国唯一。现在需要 parent context、完整 registry 或 authoritative provider 才能确定 identity。这样 Location 从“几个文本字段”变成了有明确 authority、层级和 fallback contract 的业务状态。

如果面试官继续追问，就展开：

```text
为什么不用纯 LLM
为什么 API/MCP 分工
为什么不能 tbl_shop DISTINCT 当词典
为什么需要 AMBIGUOUS
为什么设备 GPS 不能自动消歧
为什么 targeted regression 而不是每次 full regression
```

---

## Q23：这条支线有哪些不应该过度吹的地方？

当前仍然不是完整地图基础设施。

准确边界：

```text
本地 Registry 仍可继续扩大/更新
高德 Provider 只是 fallback
adcode 尚未进入 Durable Working Memory
AMBIGUOUS 当前以 clarification 为主，并没有复杂的候选选择 FSM
最后几次收口没有重新执行全量模型 Evaluation
```

所以不要说：

```text
做了一套全国行政区平台
完全解决所有地点理解
所有评测100%通过
```

更准确的说法是：

> 我围绕真实对话把消费决策 Agent 的 Location Contract 收敛成了行政实体解析、POI geocoding 和设备位置三条独立 authority，并通过 canonical state、scope transition、ambiguity 和 fallback 边界保证它们不会互相污染。

---

## 当前建议记住的最终图

```text
                         User Location Expression
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
      Administrative Region    POI / Landmark     Current Device
              │                   │                   │
  AdministrativeRegionResolver   AMap MCP           Browser GPS
              │                 maps_geo                │
      Local Registry                                  │
              │                                      │
        local NOT_FOUND                               │
              │                                      │
    AMap District WebService                          │
              │                                      │
              └──────────── Canonical Location State ┘
                               │
          targetProvince / targetCity / targetDistrict
                    targetArea / locationIntent
                               │
                     Execution Projection
                               │
                         Retrieval / Tools
```

最核心的一句话：

> **地点不是一个字符串，而是一组带有来源、意图、行政层级、实体 identity 和执行语义的业务状态；不同类型的地点必须由不同 authority 负责。**
