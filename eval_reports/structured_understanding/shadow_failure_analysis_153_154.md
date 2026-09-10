# Structured Understanding Shadow 失败归因分析

本报告只读取 Run153/154 的 Git 归档 JSON，未运行评测、未调用 LLM、未修改生产 Validator。反事实部分明确标记为 `OFFLINE_COUNTERFACTUAL`；Python 的 Unicode span 仅用于诊断，生产 Java 必须自行按原文生成 authoritative span。

## 1. Combined Summary

- 总 Turn：177（Run153 78 + Run154 99）；Structured invoked：143。
- 当前 Structured Valid Rate：52.45%；OFFLINE_COUNTERFACTUAL text-grounded Valid Rate：79.72%。
- 当前 invalid 中仅因 evidence offset 可恢复：39 Turn；反事实后仍 invalid：29 Turn。
- Multi-call：15 Turn；组成：`{"ROUTING+EXTRACTION": 15}`。

## 2. Exact Legacy Call Shapes

仅按每个 Turn 的真实 `modelCalls[].purpose` 和顺序统计；不按 route/source 推断。

| Shape | Turns | 全部 Turn 比例 | Structured-invoked Turn 比例 |
|---|---:|---:|---:|
| `EXTRACTION` | 120 | 67.80% | 83.92% |
| `NONE` | 34 | 19.21% | 0.00% |
| `ROUTING` | 8 | 4.52% | 5.59% |
| `ROUTING+EXTRACTION` | 15 | 8.47% | 10.49% |

## 3. Multi-call Turn Breakdown

| Dataset | Case | Turn | Message | Exact shape | Trigger | Legacy calls (latency/tokens) | Structured (valid/fallback, latency/tokens) |
|---|---|---:|---|---|---|---|---|
| conversation-v1 | ROBUST_PROBE_ALT_4 | 2 | 有没有评分高一点的 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 565ms/960tok; EXTRACTION 11179ms/2893tok | false/true, 20049ms/0tok |
| conversation-v1 | ROBUST_PROBE_ALT_6 | 2 | 能不能看看别家 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 409ms/963tok; EXTRACTION 6058ms/2391tok | false/true, 5640ms/2744tok |
| conversation-v1 | ADV_PROBE_AMBIG2 | 2 | 有没有近一点的 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 575ms/964tok; EXTRACTION 8201ms/2577tok | false/true, 12040ms/3507tok |
| conversation-v1 | ADV_PROBE_AMBIG3 | 2 | 还有吗 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 459ms/969tok; EXTRACTION 6032ms/2382tok | true/false, 3977ms/2502tok |
| conversation-robustness-v1 | ROBUST_CHAT_INTERRUPT_AND_RESUME | 3 | 继续，找安静一点的 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 366ms/976tok; EXTRACTION 8639ms/2595tok | true/false, 13820ms/3683tok |
| conversation-robustness-v1 | ROBUST_CITY_SWITCH_INVALIDATES_CANDIDATES | 2 | 改成福州鼓楼附近 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 464ms/999tok; EXTRACTION 9010ms/2633tok | true/false, 16274ms/3732tok |
| conversation-robustness-v1 | CASE_ROBUST_LOCATION_REFUSAL_ESCAPE | 2 | 不用管我的具体位置了，直接推荐全城最热门的 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 504ms/861tok; EXTRACTION 15406ms/3282tok | false/true, 20031ms/0tok |
| conversation-robustness-v1 | ROBUST_EXPLICIT_DEVICE_EXPLICIT_CHAIN | 3 | 还是看看北京吧 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 438ms/1093tok; EXTRACTION 15018ms/3127tok | false/true, 6955ms/3002tok |
| conversation-robustness-v1 | ROBUST_PROVINCE_TO_CITY_SCOPE | 1 | 福建有什么推荐 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 466ms/793tok; EXTRACTION 5364ms/2271tok | true/false, 13650ms/3553tok |
| conversation-robustness-v1 | ROBUST_PROVINCE_TO_CITY_SCOPE | 2 | 那厦门呢 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 1160ms/940tok; EXTRACTION 5845ms/2300tok | false/true, 8181ms/3044tok |
| conversation-robustness-v1 | ROBUST_CITY_TO_PROVINCE_SCOPE | 1 | 上海有什么推荐 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 756ms/793tok; EXTRACTION 5035ms/2290tok | false/true, 4214ms/2195tok |
| conversation-robustness-v1 | ROBUST_PROVINCE_TO_DEVICE_SCOPE | 1 | 福建有什么推荐 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 448ms/793tok; EXTRACTION 7216ms/2421tok | true/false, 6116ms/2523tok |
| conversation-robustness-v1 | ROBUST_DEVICE_TO_PROVINCE_SCOPE | 2 | 还是看看福建吧 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 342ms/982tok; EXTRACTION 9715ms/2662tok | false/true, 8459ms/3124tok |
| conversation-robustness-v1 | ROBUST_DEVICE_TO_DISTRICT_SCOPE | 2 | 在闽侯县内就可以 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 516ms/990tok; EXTRACTION 7523ms/2543tok | false/true, 12691ms/3691tok |
| conversation-robustness-v1 | ROBUST_DISTRICT_SWITCH_SCOPE | 2 | 那鼓楼区呢 | `ROUTING+EXTRACTION` | `ROUTING_ESCALATION` | ROUTING 337ms/833tok; EXTRACTION 5467ms/2259tok | true/false, 16021ms/4073tok |

15 个 multi-call Turn 的组成：`REWRITE+EXTRACTION=0`、`ROUTING+EXTRACTION=15`、`REWRITE+ROUTING=0`、其他=0`。

## 4. Evidence Failure Taxonomy

| 分类 | Evidence 数量（combined） | 解释 |
|---|---:|---|
| EXACT_SPAN_CORRECT | 312 | 当前 start/end 与原文切片完全一致 |
| TEXT_UNIQUE_BUT_OFFSET_WRONG | 83 | text 唯一存在，但 start/end 错；反事实可由 Java 唯一定位 |
| TEXT_MULTIPLE_OCCURRENCES | 0 | text 多次出现，不能只凭 text 猜 span |
| TEXT_NOT_FOUND | 0 | text 不存在于原文 |
| EVIDENCE_MISSING | 6 | 应提供 evidence 但缺失 |
| OTHER_SCHEMA_ERROR | 23 Turn | 非 evidence 的 Schema/enum/reference 错误（按 Turn 归因） |

## 5. Offline Counterfactual Valid Rate

| Run | Invoked | Current valid | Current rate | Offset-only recovered | Counterfactual valid | OFFLINE_COUNTERFACTUAL rate | Still invalid |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 153 | 60 | 31 | 51.67% | 23 | 54 | 90.00% | 6 |
| 154 | 83 | 44 | 53.01% | 16 | 60 | 72.29% | 23 |
| combined | 143 | 75 | 52.45% | 39 | 114 | 79.72% | 29 |

其中 `TEXT_UNIQUE_BUT_OFFSET_WRONG` 视为 Java 可唯一补全 span；多次出现、缺失或不存在仍 invalid。该反事实不代表线上真实指标。

## 6. Routing Escalation Cost

| Bucket | Turns | Legacy shape | Legacy latency avg/p50/p95 | Structured latency avg/p50/p95 | Legacy avg tokens | Structured avg tokens | Structured prompt/completion avg |
|---|---:|---|---|---|---:|---:|---|
| ROUTING_ESCALATION | 23 | `{"ROUTING": 8, "ROUTING+EXTRACTION": 15}` | n=23; avg=5958.78; p50=6467; p95=15084.8 | n=23; avg=8847.83; p50=6955; p95=19655.3 | 2575.65 | 2618.7 | 1845 / 773.7 |
| ROUTING_ONLY | 8 | `{"ROUTING": 8}` | n=8; avg=442.38; p50=398.0; p95=557.95 | n=8; avg=4422.75; p50=3447.0; p95=8560.6 | 838.12 | 2357.12 | 1929.5 / 427.62 |
| ROUTING_THEN_EXTRACTION | 15 | `{"ROUTING+EXTRACTION": 15}` | n=15; avg=8900.87; p50=8039; p95=15592.2 | n=15; avg=11207.87; p50=12040; p95=20036.4 | 3502.33 | 2758.2 | 1799.93 / 958.27 |

结论：Routing escalation 的 Structured Full IR 成本应与最终 Legacy shape 一起看；只发生 ROUTING 的 Turn 与还继续 EXTRACTION 的 Turn 已拆开，不能把后者误算成单次 routing 替代。

## 7. Constraint Extraction Cost

| Bucket | Turns | Legacy shape | Legacy latency avg/p50/p95 | Structured latency avg/p50/p95 | Legacy avg tokens | Structured avg tokens | Structured prompt/completion avg |
|---|---:|---|---|---|---:|---:|---|
| CONSTRAINT_EXTRACTION | 120 | `{"EXTRACTION": 120}` | n=120; avg=8187.6; p50=7265.5; p95=14414.6 | n=120; avg=12116.25; p50=11589.0; p95=20041.05 | 2553.98 | 2603.83 | 1607.64 / 996.19 |

结论：该 trigger 下 route/action 已由 Java 确定，但 Full IR 仍要求 acts、references、query、shop fact、完整 evidence 等字段；当前成本诊断支持 capability-scoped extraction，而不是无差别 Full IR。

## 8. Structured Output Cost Diagnostics

- Combined Structured IR 平均：act=1.46、reference=0.1、delta=0.98、evidence=3.31、serialized chars=416.93。
- completion token 与 evidence 数量相关系数：0.5535；与 serialized IR chars：0.5772。这只是诊断观察，不宣称严格因果。
- ROUTING_ESCALATION 输出平均：act=1.19、reference=0.24、delta=0.1、evidence=2.05、chars=290.52、prompt=2020.71、completion=847.38。
- CONSTRAINT_EXTRACTION 输出平均：act=1.52、reference=0.07、delta=1.17、evidence=3.58、chars=443.47、prompt=1929.17、completion=1195.43。

## 9. Architecture Recommendation

推荐：**方案 B：Capability-scoped Structured Understanding**。理由是：multi-call 仅 15/177，且全部是 ROUTING+EXTRACTION 组合，并非所有 Turn 都需要完整 IR；当前 evidence offset 失败占据主要无效来源，但修正后仍有 schema/ambiguous/missing 失败；Full IR 在两个 trigger 上都存在显著延迟负担，尤其 Constraint Extraction 场景不需要输出完整 references/query/shopFact。保留 Java authority 和 fail-closed，按 trigger 只请求未知 capability；Routing 后若同 Turn 还需要 extraction，复用同一 Structured Result，不进行第二次调用。方案 A 缺乏成本与必要性证据，方案 C 只优化 extraction 无法解释 routing+extraction 组合，方案 D 又丢失已验证的结构化观测价值。

## 10. Evidence Protocol V2 Recommendation

推荐继续采用候选协议（不在本轮实现）：LLM 只输出 `evidence.text`，Java 在原文中做唯一匹配并生成 `ResolvedEvidence{text,start,end}`。它可以消除当前唯一 text 的主要 offset failure、减少 start/end 重复输出 token，同时保留原文 grounding/auditability；多次出现时标记 ambiguous、不猜，text 不存在仍 fail-closed。是否让 ambiguous evidence 使整个 IR 失效，应按其是否参与 Active business decision 另定，但不能放宽安全原则。

## 结论

本报告只做归因，不修改 Structured Understanding 业务代码，不把反事实结果写成线上指标。