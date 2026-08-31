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
| Recall@3 | 0.7291 | 0.7614 | 0.7722 | **+3.2pp** | **+1.1pp** |
| NDCG@3 | 0.9355 | 0.9558 | 0.9704 | **+2.0pp** | **+1.5pp** |
| Top1 Accuracy | 0.9355 | 0.9355 | 0.9677 | **0.0pp** | **+3.2pp** |
| Constraint Violation Rate | 0.0000 | 0.0000 | 0.0000 | 0.0pp | 0.0pp |

## Failure Attribution

| Layer | V_Base | V_Sem | V_Sem_2x |
|-------|:------:|:-----:|:---------:|
| CORRECT | 66 | 69 | 71 |
| RANKING_ERROR | 39 | 36 | 34 |
| SEMANTIC_RETRIEVAL_ERROR | 7 | 7 | 7 |
| SEMANTIC_THRESHOLD_ERROR | 0 | 0 | 0 |

## Key Findings

### 1. Milvus Pre-filter Implementation

**Production pipeline** (matches production intent):
```
SQL → Hard Filter → allowedShopIds → Milvus pre-filter → TopK=80 raw document-level results
```

**Experiment pipeline** (v3, corrected):
```
SQL → Hard Filter → allowedShopIds → Milvus REST API pre-filter → TopK=80 raw document-level results
```

**Key corrections from v2**:
- Milvus REST API v2 `filter` parameter is a **pre-filter**, not a post-filter
- Filter expression uses JSON path syntax: `metadata["shopId"] in [allowedShopIds]`
- Verified against Milvus v2.5.5: pre-filtered results differ from full search + Python filter

**Pre-filter vs post-filter validation**:
- Query "人均150的日料" with allowed shops [20-55]:
  - Pre-filter: 80 results from 24 shops
  - Full search + Python filter: 1 result from 1 shop
  - This proves full search + Python filter does NOT match production pre-filter

### 2. Evidence Score = 6.0 for All Shops

Production `toRecommendation()` adds 3 points per evidence document, up to 2 documents = max 6. All 66 demo shops have 6+ review documents in `tbl_ai_review_document`, so all get 6.0.

### 3. Semantic Score Reduces Ranking Errors by 8%

V_Sem (weight=18) reduces ranking errors from 39 to 36, an **8% reduction**. V_Sem_2x (weight=36) further reduces to 34, a **13% reduction** from V_Base.

### 4. Only 6% of GT Shops Have No Semantic Signal

7 out of 112 GT shops (6%) are not found in Milvus TopK=80 with pre-filter. This is a dramatic improvement from the v2 result (77/112 = 69%) because the pre-filter ensures the ANN search is restricted to the allowed shops.

### 5. Top1 Accuracy Unchanged by Default Semantic Weight

V_Base and V_Sem have identical Top1 Accuracy (0.9355). Weight=18 is **insufficient to change the #1 recommendation**. Only when weight is doubled to 36 does Top1 improve (0.9677).

### 6. No SEMANTIC_THRESHOLD_ERROR Found

All shops that appear in the Milvus top80 results with pre-filter have at least one document with score >= 0.35. The threshold of 0.35 is below the typical COSINE similarity scores for relevant matches.

### 7. Base Ranking is Already Strong

V_Base achieves 0.9355 NDCG@3, indicating the hard filter + non-semantic ranking factors (rating, budget, distance, occasion, quiet, queue, evidence) provide a strong baseline. Semantic score is a refinement, not a replacement.

## Recommendations

1. **Keep semantic weight at 18**. Doubling to 36 shows marginal improvement and increases regression risk.

2. **Investigate the remaining 6% SEMANTIC_RETRIEVAL_ERROR rate**. 7 GT shops still don't have semantic signal even with pre-filter. Consider improved reviews or query expansion.

3. **The base ranking (V_Base) already achieves strong results**. Semantic score should be treated as a refinement.

## v2 → v3 Changes

| Change | v2 (full search + Python filter) | v3 (pre-filter) |
|--------|:---:|:---:|
| SEMANTIC_RETRIEVAL_ERROR | 77 (69%) | 7 (6%) |
| V_Sem Recall@3 | 0.7507 | 0.7614 |
| V_Sem NDCG@3 | 0.9483 | 0.9558 |
| Total documents | 220 | 976 |

The v3 pre-filter approach is now production-equivalent. The semantic signal is much more useful because the ANN search is restricted to the allowed shops.

## Per-Case Results

See `retrieval_ablation_reports/ablation_report_20260831_191253.json` for full details.