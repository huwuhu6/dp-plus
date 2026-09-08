# AI 消费决策 Agent——Working Memory 主线补充：状态投影与位置语义

> 本文是 Working Memory 主线面试资料的补充，记录 2026-09-06 真实聊天暴露的地点状态问题。重点不是 Location 功能本身，而是一个更通用的状态管理问题：**Canonical State 正确，不代表执行一定正确；从状态到执行请求的 Projection 也必须有稳定契约。**

## Q：Working Memory 里的状态明明是对的，为什么最后仍然可能推荐错？

这个问题在一次真实聊天里出现过。

用户对话大致是：

```text
Turn1：你是？
→ GENERAL_CHAT

Turn2：OK，帮我看看北京有啥好吃的吗？到时候要去玩
→ 当时错误路由为 GENERAL_CHAT

Turn3：额，随便吧，你推荐
→ START_DECISION
→ 因为北京没有进入 Task criteria，系统认为缺少搜索地点
→ CLARIFYING 请求当前位置

Turn4：用户提供浏览器 GPS，实际在福州附近
→ DECISION_EVENT / PROVIDE_LOCATION
→ 最后却推荐了杭州、泉州等距离一百多到四百多公里的商户
```

排查最终 Working Memory 时发现：

```text
deviceLocation:
  status = AVAILABLE
  source = BROWSER_GEOLOCATION
```

也就是说，**设备位置本身其实已经正确进入了 Conversation Working Memory**。

真正错误发生在后面：

```text
Working Memory / Decision State
        ↓
Execution Request Projection
        ↓
Retrieval Hard Constraints
```

当时恢复后的 DecisionRequest 大致是：

```text
latitude = 福州坐标
longitude = 福州坐标
locationStatus = AVAILABLE
useLocationScope = false
```

而约束中仍然是：

```text
nearby = false
radiusKm = -1
```

所以系统只是“知道用户坐标”，却没有把它解释成“按当前位置附近搜索”。

距离计算仍然可以用于结果展示，但距离没有成为 Hard Constraint，因此几百公里之外的商户仍可能进入候选。

这个问题给我的一个重要认识是：

> **状态系统不能只保证 Canonical State 写对，还必须保证 Canonical State → Execution Context 的投影语义一致。**

这和之前 Task V2 的“双状态视图”Bug 是两个层次的问题。

```text
Task V2 Bug：
同一 Turn 中不同 Node 看到不同 Working Memory Snapshot
→ State 本身不一致

这次 Bug：
Working Memory 中 deviceLocation 是正确的
→ 但恢复路径投影成 DecisionRequest 时丢了搜索范围语义
→ State 正确，Execution Contract 错误
```

面试时可以把两者区分开讲。

---

## Q：为什么有经纬度还不够？为什么还需要 nearby / radius / useLocationScope？

因为“有坐标”和“以坐标为搜索范围”不是同一个业务事实。

例如用户说：

```text
我人在福州，帮我找北京的餐厅
```

系统完全可以同时保存：

```text
deviceLocation = 福州
explicit targetCity = 北京
```

此时福州 GPS 可以继续存在，但不能参与北京餐饮搜索。

所以项目里实际上存在两种不同的地理信息：

```text
Conversation Scope：
deviceLocation
= 用户设备在哪

Task / Criteria Scope：
targetCity / targetArea / locationIntent / nearby / radiusKm
= 当前这一套推荐到底去哪搜
```

因此只有：

```text
latitude / longitude != null
```

并不能推出：

```text
附近搜索
```

还需要知道这次设备位置在当前决策里的业务语义。

当前契约可以概括为：

```text
显式命名目的地
→ EXPLICIT_TARGET
→ 命名地点优先
→ 不应该强制依赖设备 GPS

用户明确“我附近 / 当前定位 / X公里内”
→ CURRENT_DEVICE
→ nearby = true
→ radiusKm = 用户显式值，若未指定则默认 3km
```

这一点也是为什么“北京有什么好吃的？”在产品语义上不应该因为缺少 GPS 就被阻塞。

App 可以为了权限体验在首次使用时主动申请定位，但这是前端产品策略；后端消费决策状态机不应该把“已有明确搜索城市”和“必须拥有设备坐标”混成一个条件。

---

## Q：这次具体是怎么修的？为什么不是简单补一个 boolean？

commit `f99b01ddaf0ca8f3c58a4c7c4608adba10be309d` 的主要方向有两部分。

第一，修 Routing Contract。

原来：

```text
北京有什么好吃的？
```

可能因为没有指定菜系、预算，被模型当成 GENERAL_CHAT。

这样北京只存在于聊天文本里，没有经过 Criteria Extraction，自然也不会进入 Task criteria。

这次没有选择：

```text
GENERAL_CHAT
→ 偷偷抽取北京
→ 写入 Working Memory
```

因为这会让：

```text
我朋友刚从北京回来
北京天气怎么样
以后可能去上海玩
```

这类普通聊天也有污染业务状态的风险。

修复后的业务契约是：

```text
可信命名目的地
+
明确餐饮消费 / 推荐意图
→ START_DECISION
```

而：

```text
城市 + 天气
城市 + 景点
城市 + 普通个人经历
食物知识问答
```

仍然是 GENERAL_CHAT。

这次主要修改 Routing Model 的结构化语义契约，没有继续给 Java Routing 添加“好吃 / 吃啥 / 美食”等自然语言 contains 词表。

第二，修 Location Resume Projection。

原来的暂停恢复路径：

```text
CLARIFYING
→ PROVIDE_LOCATION
→ 写 latitude / longitude
```

没有完整建立：

```text
CURRENT_DEVICE
nearby = true
radiusKm = default 3km
```

这次把默认 nearby 半径 normalization 收敛为共享逻辑：

```text
nearby = true
+
radiusKm unspecified
→ 3km
```

而用户显式说：

```text
附近5公里
```

则必须保留 5km，不能被默认值覆盖。

这比只补：

```java
request.setUseLocationScope(true);
```

完整，因为真正决定距离硬过滤的是最终执行时的 nearby / radius 契约。

---

## Q：这次为什么说做了防过拟合，而不是只把“北京”这一句话修绿？

这次 robustness Dataset 没有只增加原始 Bad Case，而是增加了正负对照。

正样本包括不同地点和不同句式，例如：

```text
北京有什么好吃的？
去上海玩几天，有什么吃的推荐？
```

期望都是：

```text
START_DECISION
locationIntent = EXPLICIT_TARGET
```

负样本则包括：

```text
北京天气怎么样？
北京烤鸭为什么出名？
```

期望保持 GENERAL_CHAT，而且不能创建或污染餐饮 Task。

Location Resume 也同时覆盖：

```text
未指定半径
→ CURRENT_DEVICE + 3km

显式5km
→ CURRENT_DEVICE + 5km
```

因此评测不是：

```text
只要看见“北京”就 START_DECISION
```

也不是：

```text
只要 PROVIDE_LOCATION 就强制3km
```

而是在验证结构化语义边界。

定向 Run120 共 9 条，其中本轮新增 6 条全部通过；北京/上海快照进入 `EXPLICIT_TARGET`，两条定位恢复分别验证 `CURRENT_DEVICE + 3km` 和 `CURRENT_DEVICE + 5km`。

随后完整 robustness Dataset 扩展到 30 条，Run121：

```text
Route        29/30
Tool         29/30
Final Status 28/30
```

这里不能把它包装成“全部通过”。剩余失败仍需要继续按首个错误阶段归因。

---

## 当前审计发现：这次修复仍有一个需要继续收口的边界

这一点面试资料必须保持真实，不能因为 commit 已提交就假装已经完全解决。

当前 `applyProvidedLocation()` 为了保护显式命名目的地，会根据 `DecisionRequest.city / province / district` 判断 request 是否已经有 explicit destination。

这里存在一个来源语义风险：

```text
DecisionRequest.city
```

不一定来自用户显式命名地点。

正常设备定位投影本身也可能把设备所属行政区写入 request：

```text
设备位置 = 福州
→ request.city = 福州
→ useLocationScope = true
```

因此：

> `request.city != null` 不能天然等价于 `用户显式指定了 city`。

更重要的是，仓库已有一个历史回归 Case：

```text
EXPLICIT_DESTINATION_NO_DATA_DEVICE_LOCATION_RECOVERY

Turn1：
帮我找重庆附近的火锅（设备实际在福州）
→ 显式重庆直接搜索
→ 重庆无数据后进入 WAITING_RELAXATION

Turn2：
我附近
→ 用户明确要求放弃重庆范围，切换到 CURRENT_DEVICE
```

这个场景与“用户指定北京，但浏览器顺手上传福州 GPS”完全不同。

前者的真实意图是：

```text
EXPLICIT_TARGET 重庆
→ 用户主动切换
→ CURRENT_DEVICE 福州
```

后者才应该：

```text
EXPLICIT_TARGET 北京
+
设备 GPS 福州
→ 北京仍然权威，GPS 不得覆盖
```

如果仅靠：

```text
request.city 是否存在
```

无法区分这两种语义。

这说明目前 `PROVIDE_LOCATION` 这个 command 实际承载了不止一种业务含义：

```text
1. 当前设备搜索缺坐标，现在补充坐标
2. 原来是命名地点，用户主动说“改用我附近”
3. 命名地点候选确认后，内部也会转入 location continuation
```

第 3 类已经通过 `CONFIRM_RESOLVED_LOCATION_` 前缀做了区分，但第 1、2 类的业务 provenance 仍然不够明确。

因此在真正冻结 Location 这条小支线之前，应重新跑并重点检查已有：

```text
conversation-v1:
EXPLICIT_DESTINATION_NO_DATA_DEVICE_LOCATION_RECOVERY
```

如果确认回归，正确修复方向不应该继续增加：

```text
if (request.city != null) ...
```

而应该让“补充当前设备坐标”和“主动切换到当前设备”在 Command / Intent 层拥有可区分的语义，例如显式 `USE_CURRENT_DEVICE` / `SWITCH_TO_CURRENT_DEVICE`，或者至少把 location source/provenance 从 canonical criteria 明确传入，而不是根据 execution projection 反推来源。

这个问题本身也是一个很好的面试点：

> **不要用投影后的值反推状态来源。值相同不代表语义来源相同。**

`city=福州` 可能来自用户明确指定，也可能来自浏览器反向地理信息；如果业务规则依赖“它是谁提供的”，就必须保留 provenance，而不是只看最终字符串。

---

## Q：这个事故最后能总结出哪些 Working Memory 设计原则？

可以总结成四条。

第一：

```text
Canonical State 与 Execution Projection 是两个层次。
```

Working Memory 写对之后，还要验证状态如何投影成 DecisionRequest、Tool Context 和 Retrieval Constraint。

第二：

```text
显式用户意图 > 隐式设备上下文。
```

设备位置属于 Conversation Context；显式搜索目的地属于当前 Task。只有用户主动切换到 CURRENT_DEVICE 时，设备位置才应该替代命名目的地。

第三：

```text
值不等于来源。
```

`city=福州` 无法说明它来自用户输入还是浏览器定位。只要后续决策依赖来源，就需要 source / intent / provenance。

第四：

```text
同一业务语义的不同恢复路径必须产生相同 Execution Contract。
```

例如“从一开始就有 GPS”和“CLARIFYING 后补 GPS”，进入检索前都应该得到一致的 CURRENT_DEVICE + nearby + radius 语义，不能因为经过的 Pipeline 路径不同而产生不同结果。

这可以和 Working Memory 主线已有的不变量一起讲：

```text
同一 Turn 使用同一 Working Memory Snapshot
跨 Turn 用 OCC 防止旧状态覆盖新状态
Reference 绑定到用户发话时刻的 Snapshot
State → Execution 的 Projection 也必须保持语义一致
```

这四个层次分别解决不同类型的状态一致性问题。
