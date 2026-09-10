# Structured Understanding Shadow 离线分析

本报告仅读取已归档的 Run JSON；没有重新运行评测、访问应用/API、数据库或模型。`STRUCTURED_UNDERSTANDING` 为 Shadow 观测调用，以下成本对比是同一 Turn 的观测值；语义调用替代收益仅是 Active 的理论投影，不是 Active 实测。

## 总览

| Run | Dataset | Cases | Turns | Complete | Route | Tool | Final | Locality |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 153 | conversation-v1 | 40 | 78 | 28 | 37 | 32 | 40 | 40 |
| 154 | conversation-robustness-v1 | 48 | 99 | 21 | 44 | 47 | 39 | 48 |

## Run 153：conversation-v1

- Legacy semantic-call shape（仅 `REWRITE/ROUTING/EXTRACTION`）：`{"0": 18, "1": 56, "2": 4}`。
- Structured：invoked 60/78；trigger `{"CONSTRAINT_EXTRACTION": 53, "NOT_INVOKED": 18, "ROUTING_ESCALATION": 7}`；applied `0`（Shadow 必须为 0）；每 Turn >1 次 structured `0`（必须为 0）。
- validity：valid 31；invalid/fallback 29。
- validation/fallback reason：`{"acts[0].evidence": 17, "criteriaDelta[0].evidence": 11, "locationExpression.evidence": 5, "acts[1].evidence": 4, "references[0].evidence": 2, "criteriaDelta[1].evidence": 1, "criteriaDelta[2].evidence": 1, "locationExpression.evidence_missing": 1, "references[0].evidence.evidence": 1}`。
- theoretical semantic-call reduction（仅调用次数上限、不是 Active 实测）：legacy 64 → structured 60，减少 4（0.0625）。

### 同 Turn 成本（观测；Active 替代为理论投影）

| Bucket | Turns | Legacy latency ms (avg/p50/p95) | Structured latency ms (avg/p50/p95) | Avg delta | Legacy tokens (avg/p50/p95) | Structured tokens (avg/p50/p95) | Avg delta |
|---|---:|---:|---:|---:|---:|---:|---:|
| all_structured_invoked | 60 | avg 6916.92; p50 6666.0; p95 11782.1 | avg 9841.65; p50 8920.5; p95 20039.15 | 2924.73 (42.28%) | avg 2498.9; p50 2459.0; p95 3351.15 | avg 2795.17; p50 2760.5; p95 4130.65 | 296.27 (11.86%) |
| legacy_one_call | 56 | avg 6813.16; p50 6666.0; p95 11932.25 | avg 9799.88; p50 8920.5; p95 19720.25 | 2986.72 (43.84%) | avg 2425.62; p50 2447.0; p95 2933.0 | avg 2838.52; p50 2770.0; p95 4133.25 | 412.90 (17.02%) |
| legacy_two_or_more_calls | 4 | avg 8369.5; p50 7633.5; p95 11298.8 | avg 10426.5; p50 8840.0; p95 18847.65 | 2057.00 (24.58%) | avg 3524.75; p50 3447.5; p95 3806.2 | avg 2188.25; p50 2623.0; p95 3392.55 | -1336.50 (-37.92%) |

- 需要人工核对的语义轨迹候选：58（按消息中相对约束、CLEAR/REMOVE、复合意图、引用、商户事实、状态查询、位置、替代、歧义等词语筛选；不是任一侧的 gold label）。
- Legacy >=2 semantic calls 的 Turn：4；完整列表在 JSON 的 `turnsWithTwoOrMoreLegacySemanticCalls`。
- invalid/fallback 详情（含 IR 与 validation errors）在 JSON 的 `structured.invalidOrFallbackTurns`。

## Run 154：conversation-robustness-v1

- Legacy semantic-call shape（仅 `REWRITE/ROUTING/EXTRACTION`）：`{"0": 16, "1": 72, "2": 11}`。
- Structured：invoked 83/99；trigger `{"CONSTRAINT_EXTRACTION": 67, "ROUTING_ESCALATION": 16, "NOT_INVOKED": 12, "NOT_RECORDED": 4}`；applied `0`（Shadow 必须为 0）；每 Turn >1 次 structured `0`（必须为 0）。
- validity：valid 44；invalid/fallback 39。
- validation/fallback reason：`{"acts[0].evidence": 16, "criteriaDelta[0].evidence": 9, "criteriaDelta[1].evidence": 6, "locationExpression.evidence_missing": 5, "locationExpression.evidence": 4, "acts[1].evidence": 4, "acts[2].evidence": 4}`。
- theoretical semantic-call reduction（仅调用次数上限、不是 Active 实测）：legacy 94 → structured 83，减少 11（0.117）。

### 同 Turn 成本（观测；Active 替代为理论投影）

| Bucket | Turns | Legacy latency ms (avg/p50/p95) | Structured latency ms (avg/p50/p95) | Avg delta | Legacy tokens (avg/p50/p95) | Structured tokens (avg/p50/p95) | Avg delta |
|---|---:|---:|---:|---:|---:|---:|---:|
| all_structured_invoked | 83 | avg 8488.54; p50 7664; p95 15368.9 | avg 12854.83; p50 13562; p95 20041.0 | 4366.29 (51.44%) | avg 2599.81; p50 2563; p95 3567.2 | avg 2469.64; p50 2828; p95 4223.1 | -130.17 (-5.01%) |
| legacy_one_call | 72 | avg 8396.03; p50 7547.5; p95 15277.3 | avg 13063.04; p50 13633.0; p95 20042.35 | 4667.01 (55.59%) | avg 2463.17; p50 2529.0; p95 3107.75 | avg 2393.89; p50 2740.5; p95 4264.65 | -69.28 (-2.81%) |
| legacy_two_or_more_calls | 11 | avg 9094.09; p50 8039; p95 15683.0 | avg 11492; p50 12691; p95 18152.5 | 2397.91 (26.37%) | avg 3494.18; p50 3533; p95 4181.5 | avg 2965.45; p50 3124; p95 3902.5 | -528.73 (-15.13%) |

- 需要人工核对的语义轨迹候选：70（按消息中相对约束、CLEAR/REMOVE、复合意图、引用、商户事实、状态查询、位置、替代、歧义等词语筛选；不是任一侧的 gold label）。
- Legacy >=2 semantic calls 的 Turn：11；完整列表在 JSON 的 `turnsWithTwoOrMoreLegacySemanticCalls`。
- invalid/fallback 详情（含 IR 与 validation errors）在 JSON 的 `structured.invalidOrFallbackTurns`。

## 与已归档 off baseline 的安全观察

这只是不同运行之间的结果对照，不是因果结论；模型与外部 Tool 的波动仍可能影响指标。

| Shadow Run | Off baseline | Complete Δ | Route Δ | Tool Δ | Final Δ | Locality Δ |
|---:|---:|---:|---:|---:|---:|---:|
| 153 | 148 | 1 | 1 | 1 | 0 | 0 |
| 154 | 147 | 0 | 0 | 0 | 0 | 0 |

## 决策

`STOP_OR_REDESIGN`。依据见 JSON 的原始计数、逐 Turn paired cost 与人工 review 候选。此决定不把 Shadow 的观测调用当作 Active 收益，也不以单次 E2E 指标宣称因果。
