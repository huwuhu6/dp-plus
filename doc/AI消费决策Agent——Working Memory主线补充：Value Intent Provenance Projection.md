# AI 消费决策 Agent——Working Memory 主线补充：Value / Intent / Provenance / Projection

> 本文记录 Location State / Projection provenance 闭环。它不是 Location 功能说明，而是 Working Memory 主线中的一个状态建模案例：**同一个字段值，不等于同一个业务意图；执行投影也不能反推状态来源。**

## Q：这次真正的 Bug 是什么？

真实回归用例：

```text
Turn1：
帮我找重庆附近的火锅
设备实际在福州

→ 当前任务是 EXPLICIT_TARGET 重庆
→ 重庆无结果，进入 WAITING_RELAXATION

Turn2：
我附近
```

用户第二轮已经明确表示：

```text
放弃重庆范围
→ 改用 CURRENT_DEVICE
```

但修复前 Run122 的实际状态仍然是：

```text
targetCity = 重庆
locationIntent = EXPLICIT_TARGET
nearby = true
radiusKm = 3
```

也就是出现了一个语义上非常危险的混合状态：

```text
显式地点 = 重庆
+
附近搜索 = true
```

根因并不是模型没理解“我附近”，而是执行层用错了状态来源。

原逻辑在 `applyProvidedLocation()` 中根据：

```java
request.getCity()
request.getProvince()
request.getDistrict()
```

是否为空，反推当前是否存在“用户显式目的地”。

问题在于 `DecisionRequest` 是执行投影对象。

例如：

```text
request.city = 福州
```

既可能来自：

```text
用户明确说“帮我找福州餐厅”
```

也可能来自：

```text
浏览器 GPS
→ reverse geocoding
→ 福州市
```

所以：

> `request.city = 福州` 只描述一个最终值，不能证明这个值来自哪里。

只要业务决策依赖“是谁提供了这个地点”，就不能从 Projection Value 反推 Provenance。

---

## Q：为什么这是 Working Memory 问题，而不只是 Location Bug？

因为它暴露的是状态系统中四个不同概念被混在了一起：

```text
Value
Intent
Provenance
Projection
```

可以这样理解。

### Value

最终值是什么：

```text
city = 福州
radiusKm = 3
```

### Intent

这个值当前在业务上的含义是什么：

```text
EXPLICIT_TARGET
CURRENT_DEVICE
UNSPECIFIED
```

### Provenance

这个信息从哪里来的：

```text
用户明确输入
设备定位
系统默认值
系统推导
```

### Projection

为了执行检索，最终怎样把 canonical state 转成：

```text
DecisionRequest.city
latitude
longitude
useLocationScope
```

这次 Bug 就是：

```text
Projection.city
```

被错误地拿来反推：

```text
Intent / Provenance
```

所以正确原则是：

> **Projection 只能消费 Canonical State，不能反过来成为 Canonical State 的来源。**

---

## Q：最终怎么修？

commit：

```text
ec39bdc57de3674d9c25d170f84f6416ae9b5bc7
fix: 修复地点意图来源与范围切换
```

核心修改不是增加新的字符串规则，而是重新确定权威来源。

### 1. Explicit Target 判断只看业务状态

现在显式目的地判断基于：

```text
DecisionConstraints.locationIntent
DecisionConstraints.targetCity
DecisionConstraints.targetArea
```

而不是：

```text
DecisionRequest.city/province/district
```

也就是说：

```text
Canonical Criteria
→ 决定业务语义

DecisionRequest
→ 只负责执行投影
```

---

### 2. WAITING_RELAXATION + “我附近”被定义成明确状态转换

这类输入不再理解为：

```text
给当前重庆任务补几个 GPS 字段
```

而是一个真正的 Scope Switch：

```text
EXPLICIT_TARGET 重庆
        ↓
用户：我附近
        ↓
CURRENT_DEVICE
```

切换时需要同时处理：

```text
清空 targetCity
targetArea
清空 Task searchLocation
让旧 candidatePool / focusedShop 失效
切换 locationIntent = CURRENT_DEVICE
nearby = true
```

如果用户之前有明确半径，则保留；否则进入默认 3km 规则。

这说明状态切换不能只改一个字段：

```text
locationIntent = CURRENT_DEVICE
```

还必须清理所有只属于旧搜索 Universe 的派生状态。

---

### 3. CURRENT_DEVICE → EXPLICIT_TARGET 也必须对称处理

另一方向同样可能泄漏状态。

例如：

```text
Turn1：
我附近找火锅
→ CURRENT_DEVICE
→ nearby=true
→ radius=3km

Turn2：
换成北京的日料
→ EXPLICIT_TARGET 北京
```

如果只修改：

```text
targetCity = 北京
```

但继续保留：

```text
nearby=true
radius=3km
```

就会产生：

```text
北京
+
距离福州设备3km
```

这种不可能的混合状态。

因此 Merger 在：

```text
CURRENT_DEVICE
→ EXPLICIT_TARGET
```

且用户没有重新声明 nearby/radius 时，会清掉设备范围约束。

所以最终形成一个对称契约：

```text
EXPLICIT_TARGET
→ CURRENT_DEVICE
清理命名地点 scope

CURRENT_DEVICE
→ EXPLICIT_TARGET
清理设备 nearby/radius scope
```

这个设计比给每个 Bad Case 写单独 if 更稳定。

---

## Q：为什么不能简单说“显式地点优先级永远高于设备位置”？

因为这句话只对一半。

例如：

```text
用户：帮我找北京餐厅
设备：福州
```

这时候当然应该：

```text
北京 > 福州设备位置
```

但是用户下一轮如果明确说：

```text
那我附近呢？
```

此时新用户意图应该覆盖旧的北京。

所以真正的不变量不是：

```text
EXPLICIT_TARGET 永远优先
```

而是：

> **当前 Turn 最新的显式用户意图优先；设备位置只有在 CURRENT_DEVICE 语义成立时才参与搜索。**

也就是说优先级必须和状态转换结合，而不是写成静态字段优先级。

---

## Q：这次为什么没有新建一套 Location State Machine？

因为现有状态已经足够表达核心语义：

```text
locationIntent = EXPLICIT_TARGET
locationIntent = CURRENT_DEVICE
```

真正的问题不是状态维度不够，而是代码绕过这些业务状态，直接根据 `DecisionRequest` 猜来源。

所以本轮选择：

```text
修正 authority boundary
```

而不是：

```text
再建一个 LocationWorkflow / LocationSaga / LocationContext
```

这是一个典型的最小架构修复：

> 如果现有模型已经能表达业务语义，就优先修职责边界，不要为了一个 Bug 增加新的抽象层。

---

## Q：如何证明不是只把重庆这个 Case 写绿？

这次没有只测试：

```text
重庆 → 我附近
```

而是补了状态迁移矩阵。

### Explicit → Device

```text
重庆 / EXPLICIT_TARGET
→ 我附近
→ 空 targetCity / CURRENT_DEVICE + 3km
```

### Device → Explicit

```text
CURRENT_DEVICE 福州附近
→ 换成北京日料
→ 北京 / EXPLICIT_TARGET
→ nearby=false
→ radius=-1
```

### Explicit → Device → Explicit

```text
北京
→ 我附近
→ 上海
```

验证三个 Scope 连续切换不会残留旧地点或半径。

### Named Location Confirmation

命名地点候选确认最终内部也会进入 continuation，但不能被误认为浏览器 Current Device。

这一条继续由：

```text
CONFIRM_RESOLVED_LOCATION_
```

做结构化 command 区分，而不是通过自然语言判断。

因此这次测试验证的是：

```text
状态转换关系
```

而不是：

```text
几个城市名
```

生产代码没有增加城市名单、CaseCode 特判或自然语言 contains / Regex。

---

## Q：这次有哪些拿得出手的验证数据？

最重要的是有一个明确的“修复前失败 → 修复后通过”轨迹。

修复前 Run122：

```text
EXPLICIT_DESTINATION_NO_DATA_DEVICE_LOCATION_RECOVERY

第二轮：
targetCity = 重庆
locationIntent = EXPLICIT_TARGET
nearby = true
radiusKm = 3

FAIL
```

修复后：

```text
重庆 / EXPLICIT_TARGET
→ 空 targetCity / CURRENT_DEVICE + 3km
```

通过。

定向 Run123：

```text
4/4
```

覆盖：

```text
Explicit → Device
Device → Explicit
Explicit → Device → Explicit
Named Location Confirmation
```

完整 robustness Run124，33 Case：

```text
Route        32/33
Tool         32/33
Final Status 32/33
Locality     32/33
```

新增 Location transition Case 全部通过。

conversation-v1 Run125，40 Case：

```text
Route        38/40
Tool         33/40
Final Status 40/40
Locality     40/40
```

关键历史回归：

```text
EXPLICIT_DESTINATION_NO_DATA_DEVICE_LOCATION_RECOVERY
```

已经从修复前失败变为通过。

面试时不要说：

```text
所有评测100%通过
```

准确说法是：

> 我针对 Location Scope Transition 做了 4 条定向状态迁移评测全部通过；完整 robustness 33 条中 Route/Tool/Final/Locality 均为 32/33，conversation-v1 的 Final Status 和 Locality 为 40/40，关键历史回归用例从失败恢复为通过。

---

## Q：这个故事最适合怎么总结？

可以这样讲：

> 我在做 Working Memory 时后来发现一个比较隐蔽的问题：状态本身可能是对的，但执行投影仍然会错。一次真实对话里，设备位置已经正确存进 Working Memory，但恢复搜索时只写了经纬度，没有把 CURRENT_DEVICE / nearby / radius 完整投影到执行约束，所以出现了福州用户推荐到杭州的情况。第一次修复后我又做历史回归，发现代码还在用 `DecisionRequest.city` 反推“是否显式地点”，但这个 city 既可能来自用户输入，也可能来自 GPS 反向地理编码，于是“重庆无结果 → 我附近”仍然无法真正切换 Scope。最后我把 `DecisionConstraints.locationIntent` 收敛成业务权威来源，并对 Explicit→Device、Device→Explicit 做对称清理，再通过状态迁移矩阵验证。

这里真正的教训不是 Location，而是：

```text
Value ≠ Intent
Intent ≠ Provenance
Canonical State ≠ Execution Projection
```

以及：

> **不要用执行投影后的字段去反推业务状态来源。**

这条原则可以推广到任何 Agent Working Memory 场景，例如用户资料、工具结果、默认参数、模型推断值和外部系统字段，都必须明确谁是 canonical source，谁只是 projection。
