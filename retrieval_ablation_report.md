# Retrieval Ablation Experiment Report

## Experiment Info
- **Date**: 2026-08-31
- **Dataset**: ablation-v1 (35 cases: 31 active + 4 relaxation)
- **Ground Truth**: 112 shops (105 grade 2, 7 grade 1)
- **Coverage**: 14 scenarios (结构化约束, 结构化+语义偏好, 仅菜系, 地理+菜系, 地名嵌入菜品名, 无明显语义偏好, 烤肉规范, 牛排规范, 寿司规范, 港式规范, 营业时间边界, 零结果, 福州数据, 无语义证据)

## Three Variants

| Variant | Semantic Retrieval | Semantic Weight | Description |
|---------|:---:|:---:|-------------|
| V_Base  | N/A  | 0   | Hard Filter + full ranking, NO semantic contribution |
| V_Sem   | ✅   | 18  | Hard Filter + full ranking + semanticScore × 18 (current production) |
| V_Sem_2x | ✅  | 36  | Hard Filter + full ranking + semanticScore × 36 (sanity check) |

## Core Metrics

| Metric | V_Base | V_Sem | V_Sem_2x | Δ(V_Sem-V_Base) | Δ(V_Sem_2x-V_Sem) |
|--------|:------:|:-----:|:---------:|:----------------:|:------------------:|
| Recall@3 | 0.7291 | 0.7507 | 0.7722 | **+2.2pp** | **+2.2pp** |
| NDCG@3 | 0.9355 | 0.9483 | 0.9684 | **+1.3pp** | **+2.0pp** |
| Top1 Accuracy | 0.9355 | 0.9355 | 0.9677 | **0.0pp** | **+3.2pp** |
| Constraint Violation Rate | 0.0000 | 0.0000 | 0.0000 | 0.0pp | 0.0pp |

## Failure Attribution

| Layer | V_Base | V_Sem | V_Sem_2x |
|-------|:------:|:-----:|:---------:|
| CORRECT | 21 | 29 | 31 |
| RANKING_ERROR | 14 | 6 | 4 |
| SEMANTIC_RETRIEVAL_ERROR | 77 | 77 | 77 |

## Key Findings

### 1. Semantic Score Reduces Ranking Errors by 57%

The primary benefit of semantic scoring is in ranking: V_Sem (weight=18) reduces ranking errors from 14 to 6, a **57% reduction**. V_Sem_2x (weight=36) further reduces ranking errors to 4, a **71% reduction** from V_Base.

### 2. 69% of GT Shops Have No Semantic Signal

77 out of 112 GT shops (69%) are not found in Milvus topK=80 for their query. This means the semantic score contributes **zero** to ranking for the majority of GT shops. The semantic signal only helps the 35 shops that are present in the Milvus results.

**Possible causes**:
- Demo shop reviews are generic and don't match specific query intents
- The 1024-dim embedding space may not capture fine-grained cuisine/preference distinctions
- TopK=80 may be insufficient for near-duplicate dense vectors

### 3. Top1 Accuracy Unchanged by Default Semantic Weight

V_Base and V_Sem have identical Top1 Accuracy (0.9355). The default weight of 18 is **insufficient to change the #1 recommendation** for any case. Only when weight is doubled to 36 (V_Sem_2x) does Top1 improve (0.9677).

### 4. Semantic Score Can Cause Regression

Case **ABL_033** (安静的日料店) shows a clear regression:
- V_Base: Recall@3 = 1.00 (all 3 GT shops in top3)
- V_Sem: Recall@3 = 0.67 (1 GT shop pushed out of top3)
- V_Sem_2x: Recall@3 = 0.33 (2 GT shops pushed out of top3)

**Root cause**: GT shops 24 (鳗诚屋) and 25 (和味亭) have no semantic score (SEMANTIC_RETRIEVAL_ERROR), so they receive no semantic boost. Non-GT shops with higher semantic scores push them out of the top3.

### 5. Semantic Score is Critical for Some Cases

Case **ABL_030** (寿司 → 日料 canonicalization):
- V_Base: Recall@3 = 0.00 (shop 21 三上 not in top3)
- V_Sem: Recall@3 = 1.00 (semantic score pushes 21 into top3)
- This is the strongest evidence for semantic score's contribution

Case **ABL_009** (重庆鸡公煲):
- V_Base: Recall@3 = 0.00, V_Sem: Recall@3 = 0.00
- V_Sem_2x: Recall@3 = 1.00 (only at double weight)
- Semantic signal exists but is weak; needs higher weight

### 6. Constraint Violation Rate is Zero

All three variants maintain 0% constraint violation rate, confirming that the hard filter correctly enforces budget, cuisine, radius, and open hours before ranking.

## Recommendations

1. **Keep semantic weight at 18** as the default. Doubling to 36 shows marginal improvement and increases regression risk.

2. **Investigate the 69% SEMANTIC_RETRIEVAL_ERROR rate**. The high rate suggests many shops' profile/review documents don't match query intents well. Consider:
   - Review quality/salience filtering
   - Query expansion for the semantic retrieval step
   - Increasing topK beyond 80

3. **Monitor for regression** in cases where GT shops lack semantic signals. The current weight=18 has limited regression impact (1 case), but higher weights amplify the problem.

4. **The base ranking (V_Base) already achieves 0.9355 NDCG@3**, indicating that the hard filter + non-semantic ranking factors (rating, budget, distance, occasion, quiet, queue, evidence) provide a strong baseline. Semantic score is a refinement, not a replacement.

## Detailed Per-Case Results

See `retrieval_ablation_reports/ablation_report_20260831_183630.json` for full per-case details.