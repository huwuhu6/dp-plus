# Retrieval Ablation Experiment Report

## Experiment Info
- **Date**: 2026-08-31
- **Dataset**: ablation-v1 (35 cases: 31 active + 4 relaxation)
- **Ground Truth**: 112 shops (105 grade 2, 7 grade 1)
- **Coverage**: 14 scenarios

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
| SEMANTIC_THRESHOLD_ERROR | 0 | 0 | 0 |

## Key Findings

### 1. Milvus Snapshot Implementation

**Snapshot pipeline** (matches production intent):
```
SQL → Hard Filter → allowedShopIds → Milvus (full collection top80) → Python allowedShopIds filter → raw document-level results
```

**Experiment pipeline**:
```
raw document-level results → MAX aggregation → 0.35 threshold → scoring
```

**Key corrections from v1**:
- Snapshot preserves raw document-level results (no MAX, no threshold in snapshot)
- Milvus search is full collection (REST API filter is post-filter; production pre-filter is replicated by Python filtering)
- Experiment phase performs MAX aggregation + threshold independently for each variant

### 2. Evidence Score = 6.0 for All Shops

Production `toRecommendation()` adds 3 points per evidence document, up to 2 documents = max 6. All 66 demo shops have 6+ review documents in `tbl_ai_review_document`, so all get 6.0.

### 3. Semantic Score Reduces Ranking Errors by 57%

V_Sem (weight=18) reduces ranking errors from 14 to 6, a **57% reduction**. V_Sem_2x (weight=36) further reduces to 4, a **71% reduction** from V_Base.

### 4. 69% of GT Shops Have No Semantic Signal

77 out of 112 GT shops (69%) are not found in Milvus topK=80. This means the semantic score provides **zero contribution** for the majority of GT shops. The semantic signal only helps the 35 shops that are present in the Milvus results.

### 5. Top1 Accuracy Unchanged by Default Semantic Weight

V_Base and V_Sem have identical Top1 Accuracy (0.9355). Weight=18 is **insufficient to change the #1 recommendation**. Only when weight is doubled to 36 does Top1 improve (0.9677).

### 6. No SEMANTIC_THRESHOLD_ERROR Found

All shops that appear in the Milvus top80 results have at least one document with score >= 0.35. The threshold of 0.35 is below the typical COSINE similarity scores for relevant matches.

### 7. Base Ranking is Already Strong

V_Base achieves 0.9355 NDCG@3, indicating the hard filter + non-semantic ranking factors (rating, budget, distance, occasion, quiet, queue, evidence) provide a strong baseline. Semantic score is a refinement, not a replacement.

## Recommendations

1. **Keep semantic weight at 18**. Doubling to 36 shows marginal improvement and increases regression risk.

2. **Investigate the 69% SEMANTIC_RETRIEVAL_ERROR rate**. Many shops' documents don't match query intents in the top80 global results. Consider improved reviews or query expansion.

3. **The base ranking (V_Base) already achieves strong results**. Semantic score should be treated as a refinement.

## Per-Case Results

See `retrieval_ablation_reports/ablation_report_20260831_185538.json` for full details.