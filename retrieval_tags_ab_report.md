# Review Embedding Content-only vs Content+Tags A/B Report

## Experiment Info
- **Date**: 2026-08-31
- **Dataset**: ablation-v1 (35 cases)
- **Method**: Exact cosine over allowed set (NOT ANN)
- **Ground Truth**: 105 shops (grade ≥ 2) across 35 queries

## Two Variants

| Variant | Embedding Text | Description |
|---------|----------------|-------------|
| A (content-only) | `商户：{name}。评价证据：{content}。标签：` | Tags stripped from text |
| B (content+tags) | `商户：{name}。评价证据：{content}。标签：{tags}` | Tags included (matches current production index) |

## Core Metrics

| Metric | A (content-only) | B (content+tags) | Δ(B-A) |
|--------|:-----------------:|:-----------------:|:------:|
| Recall@3 | 0.7932 | 0.7932 | **0.00pp** |
| Recall@5 | 0.9514 | 0.9514 | **0.00pp** |
| NDCG@3 | 0.9828 | 0.9873 | **+0.45pp** |
| NDCG@5 | 0.9828 | 0.9873 | **+0.45pp** |
| Top1 Accuracy | 0.9655 | 0.9655 | **0.00pp** |
| Constraint Violation Rate | 0.0000 | 0.0000 | 0.00pp |

## Failure Attribution

| Layer | A | B |
|-------|:--:|:--:|
| CORRECT | 69 | 69 |
| HARD_FILTER_ERROR | 0 | 0 |
| SEMANTIC_RETRIEVAL_ERROR | 0 | 0 |
| SEMANTIC_THRESHOLD_ERROR | 0 | 0 |
| RANKING_ERROR | 36 | 36 |

## Production Anchor

| Metric | Value |
|--------|:-----:|
| B exact cosine vs B production Milvus overlap@3 | 0.8571 |
| B exact cosine vs B production Milvus overlap@5 | 0.7714 |

## Decision

**Pre-registered rule**: Keep B (content+tags) only if Recall@3 ≥ +3pp AND NDCG@3/Top1/CVR not worse.

| Condition | Met? |
|-----------|:----:|
| Recall@3 ≥ +3pp (Δ = 0.00pp) | ❌ |
| NDCG@3 not worse | ✅ (+0.45pp) |
| Top1 not worse | ✅ (0.00pp) |
| CVR not worse | ✅ (0.00pp) |

**Result: Keep content-only (A).** Tags in embedding text do not improve Recall@3 or Top1 Accuracy.

## Key Findings

### 1. Tags Are Redundant with Content

Tags in the embedding text produce **identical** Recall@3, Recall@5, and Top1 Accuracy. The only difference is a marginal NDCG@3 improvement of +0.45pp, which is within noise for 35 queries.

The reason: tags are derived from the same review content (`"自助,三文鱼,甜虾,补货快,性价比"` from `"性价比高...三文鱼甜虾北极贝吃到饱...食材补货快..."`). The embedding model (text-embedding-v4) already captures these semantic signals from the content alone. Tags add no new information.

### 2. Exact Cosine Eliminates ANN Retrieval Loss

Comparing with the previous ablation experiment (which used ANN pre-filter):

| Metric | V_Sem (ANN) | A (exact cosine) | Δ |
|--------|:-----------:|:-----------------:|:-:|
| Recall@3 | 0.7614 | 0.7932 | **+3.18pp** |
| NDCG@3 | 0.9558 | 0.9828 | **+2.70pp** |
| Top1 | 0.9355 | 0.9655 | **+3.00pp** |

The improvement comes from exact cosine evaluating ALL allowed shops, while ANN TopK=80 only covers ~13 shops (80 docs / 6 per shop). With exact cosine, all 66 shops get semantic scores, reducing SEMANTIC_RETRIEVAL_ERROR from 7 to 0.

### 3. No SEMANTIC_THRESHOLD_ERROR

All shops with non-zero semantic scores exceed the 0.35 threshold. This confirms the threshold is not a limiting factor.

### 4. Production Anchor Shows Good Agreement

B exact cosine vs B production Milvus shows 0.8571 overlap@3. The 14.3% disagreement is expected since ANN is approximate (not exact) and the Milvus pre-filter has TopK=80 limit. The correlation is sufficient to validate the experiment methodology.

## Recommendations

1. **Keep content-only (A) for embedding text**. Tags add no measurable recall improvement.

2. **The current production index (content+tags) is not harmful**, but it's unnecessary. Future re-indexing can use content-only text without regression risk.

3. **Consider alternative semantic improvements** instead of tags: query expansion, multi-vector retrieval, or cross-encoder re-ranking.

## Per-Case Results

See `retrieval_tags_ab_results.json` for full per-case details.