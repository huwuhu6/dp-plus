# Routing Fusion V2 Shadow 离线分析（Run155/156）

本报告只读取 Git 归档 JSON，不访问应用、数据库、HTTP、模型或重新运行评测。V2 仍是 Shadow 旁路，没有业务 authority。Python evidence 统计仅用于离线诊断，生产 evidence authority 仍是 Java `EvidenceGrounder`。

## 实验元数据

| Run | Dataset | Mode | Cases | Turns | Commit at run | Branch at archive |
|---:|---|---|---:|---:|---|---|
| 155 | conversation-v1 | shadow | 40 | 78 | `99dd6ca-dirty` | `feat/structured-understanding-experiment` |
| 156 | conversation-robustness-v1 | shadow | 48 | 99 | `99dd6ca-dirty` | `feat/structured-understanding-experiment` |

## 调用范围与 V2 Funnel

### Run 155（conversation-v1）

- V2 invoked：**7/78（0.0897）**；NOT_INVOKED=71。trigger={"NOT_INVOKED": 71, "ROUTING_ESCALATION": 7}。
- `CONSTRAINT_EXTRACTION` trigger violation：**0**；extraction-only shape 误调用 V2：**0**；同 Turn >1 次 V2：**0**。
- Legacy shape：`{"EXTRACTION": 56, "NONE": 15, "ROUTING": 3, "ROUTING+EXTRACTION": 4}`。
- V2 valid/fallback：**7/0**，valid rate=1.0；criteriaReusable=4。
- Active eligibility（离线等价 gate）：valid=7，reusable=4，offline eligible=3；reject={"criteria_not_reusable": 3, "eligible": 3, "location_expression_present": 1}。
- evidence status（离线 `message.count(text)` 诊断）：`{"RESOLVED": 10, "MISSING": 6}`；start/end 提交或错误痕迹：0。
- validation 分类：`{}`。
### Run 156（conversation-robustness-v1）

- V2 invoked：**16/99（0.1616）**；NOT_INVOKED=83。trigger={"NOT_INVOKED": 79, "ROUTING_ESCALATION": 16, "NOT_RECORDED": 4}。
- `CONSTRAINT_EXTRACTION` trigger violation：**0**；extraction-only shape 误调用 V2：**0**；同 Turn >1 次 V2：**0**。
- Legacy shape：`{"EXTRACTION": 68, "ROUTING": 5, "ROUTING+EXTRACTION": 11, "NONE": 15}`。
- V2 valid/fallback：**10/6**，valid rate=0.625；criteriaReusable=5。
- Active eligibility（离线等价 gate）：valid=10，reusable=5，offline eligible=1；reject={"criteria_not_reusable": 5, "invalid": 6, "location_expression_present": 4, "eligible": 1}。
- evidence status（离线 `message.count(text)` 诊断）：`{"RESOLVED": 33, "MISSING": 9, "NOT_FOUND": 1}`；start/end 提交或错误痕迹：0。
- validation 分类：`{"location evidenceText grounding": 1, "schema/enum": 8}`。

## Paired cost（仅同 Turn、同一 Shadow 观测）

| Run | Bucket | Turns | Legacy latency avg/p50/p95 | V2 latency avg/p50/p95 | latency Δ | Legacy tokens avg/p50/p95 | V2 tokens avg/p50/p95 | tokens Δ |
|---:|---|---:|---|---|---|---|---|---|
| 155 | ROUTING | 3 | 741.33/492/1194.0 | 1040.67/1067/1122.8 | {'absolute': 299.34000000000003, 'percent': 40.38} | 873.33/859/954.4 | 1025.67/980/1160.0 | {'absolute': 152.34000000000003, 'percent': 17.44} |
| 155 | ROUTING+EXTRACTION | 4 | 8638.5/8565.0/9353.7 | 1185.25/1177.5/1556.15 | {'absolute': -7453.25, 'percent': -86.28} | 3455.5/3452.5/3524.2 | 1197.5/1198.0/1217.4 | {'absolute': -2258.0, 'percent': -65.35} |
| 156 | ROUTING | 5 | 604.8/456/949.2 | 1169.8/1163/1333.4 | {'absolute': 565.0, 'percent': 93.42} | 820.4/793/902.4 | 957.4/911/1096.2 | {'absolute': 137.0, 'percent': 16.7} |
| 156 | ROUTING+EXTRACTION | 11 | 9428.82/8063/18971.5 | 1468.64/1457/2365.0 | {'absolute': -7960.179999999999, 'percent': -84.42} | 3542.73/3473/4404.5 | 1139/1210/1333.0 | {'absolute': -2403.73, 'percent': -67.85} |

## E2E safety observation

不同运行之间只作安全观察，不作因果归因。
- Run 155 vs Run 153（同 dataset）：complete Δ=0, route Δ=0, tool Δ=0, final Δ=0, locality Δ=0.
- Run 155 vs Run 148（同 dataset）：complete Δ=1, route Δ=1, tool Δ=1, final Δ=0, locality Δ=0.
- Run 156 vs Run 154（同 dataset）：complete Δ=0, route Δ=0, tool Δ=0, final Δ=-1, locality Δ=0.
- Run 156 vs Run 147（同 dataset）：complete Δ=0, route Δ=0, tool Δ=0, final Δ=-1, locality Δ=0.

## Criteria Fusion 人工复核（全部 ROUTING+EXTRACTION 且 V2 invoked）

以下不是 gold label；每一行都标为 `NEEDS_MANUAL_REVIEW`，重点核对 legacy criteria 与 V2 delta 是否表达同一语义。

共 15 条；JSON 保存完整字段。优先复核前 10 条：

| Case | Turn | Legacy route | Message | V2 acts | Delta | Valid/Reusable | Offline active |
|---|---:|---|---|---|---|---|---|
| ROBUST_PROBE_ALT_4 | 2 | START_DECISION | 有没有评分高一点的 | [{"type": "REQUEST_RECOMMENDATION", "evidenceText": "有没有评分高一点的"}] | [{"field": "PREFERENCE", "operation": "ADD", "rawValue": "高评分", "evidenceText": "评分高一点"}] | True/True | True |
| ROBUST_PROBE_ALT_6 | 2 | START_DECISION | 能不能看看别家 | [{"type": "EXPLORE_ALTERNATIVE", "evidenceText": "能不能看看别家"}] | [] | True/True | True |
| ADV_PROBE_AMBIG2 | 2 | START_DECISION | 有没有近一点的 | [{"type": "EXPLORE_ALTERNATIVE", "evidenceText": "有没有近一点的"}] | [{"field": "RADIUS_KM", "operation": "INCREASE", "rawValue": "5.0", "evidenceText": "近一点"}] | True/True | False |
| ADV_PROBE_AMBIG3 | 2 | START_DECISION | 还有吗 | [{"type": "EXPLORE_ALTERNATIVE", "evidenceText": "还有吗"}] | [] | True/True | True |
| ROBUST_CHAT_INTERRUPT_AND_RESUME | 3 | START_DECISION | 继续，找安静一点的 | [{"type": "MUTATE_CRITERIA", "evidenceText": "安静一点"}] | [{"field": "PREFERENCE", "operation": "ADD", "rawValue": "安静", "evidenceText": "安静一点"}] | False/False | False |
| ROBUST_CITY_SWITCH_INVALIDATES_CANDIDATES | 2 | START_DECISION | 改成福州鼓楼附近 | [{"type": "MUTATE_CRITERIA", "evidenceText": "改成福州鼓楼附近"}] | [{"operation": "SET", "rawValue": "福建省", "evidenceText": "福州鼓楼附近"}, {"operation": "SET", "rawValue": "福州市", "evidenceText": "福州鼓楼附近"}, {"operation": "SET", "rawValue": "鼓楼区", "evidenceText": "福州鼓楼附近"}] | False/False | False |
| CASE_ROBUST_LOCATION_REFUSAL_ESCAPE | 2 | START_DECISION | 不用管我的具体位置了，直接推荐全城最热门的 | [{"type": "REQUEST_RECOMMENDATION", "evidenceText": "直接推荐全城最热门的"}] | [{"field": "NEARBY", "operation": "CLEAR", "evidenceText": "不用管我的具体位置了"}, {"field": "CUISINE", "operation": "SET", "rawValue": "川菜", "evidenceText": "全城最热门的"}] | True/True | False |
| ROBUST_EXPLICIT_DEVICE_EXPLICIT_CHAIN | 3 | START_DECISION | 还是看看北京吧 | [{"type": "MUTATE_CRITERIA", "evidenceText": "还是看看北京吧"}] | [{"operation": "SET", "rawValue": "北京", "evidenceText": "北京"}] | False/False | False |
| ROBUST_PROVINCE_TO_CITY_SCOPE | 1 | START_DECISION | 福建有什么推荐 | [{"type": "REQUEST_RECOMMENDATION", "evidenceText": "福建有什么推荐"}] | [] | True/True | False |
| ROBUST_PROVINCE_TO_CITY_SCOPE | 2 | START_DECISION | 那厦门呢 | [{"type": "REQUEST_RECOMMENDATION", "evidenceText": "那厦门呢"}] | [{"operation": "SET", "rawValue": "福建省", "evidenceText": "那厦门呢"}, {"operation": "SET", "rawValue": "厦门市", "evidenceText": "那厦门呢"}] | False/False | False |

## 决策

**STOP_ROUTING_FUSION**。本次仅验证 Shadow 观测契约；未运行 Active 或 holdout。
