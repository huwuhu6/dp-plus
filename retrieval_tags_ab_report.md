# Review Embedding Content-only vs Content+Tags A/B Report (v2 — Profile + Review)

## Experiment Info
- **Date**: 2026-08-31
- **Dataset**: ablation-v1 (35 cases)
- **Method**: Exact cosine over allowed set (Profile + Review, MAX aggregation across all document types)
- **Ground Truth**: 105 shops (grade ≥ 2) across 35 queries
- **Profile documents**: 66 (shared, fixed across A/B)

## Two Variants

| Variant | Profile Text | Review Text | Description |
|---------|-------------|-------------|-------------|
| A (content-only) | Production `profileDocument()` | `商户：{name}。评价证据：{content}。标签：` | Tags stripped from review text |
| B (content+tags) | Same as A (identical) | `商户：{name}。评价证据：{content}。标签：{tags}` | Tags included in review text |

**Only variable**: Review embedding text tags. Profile embeddings are identical across A/B.

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

## Profile-dominance Rate

| Variant | Rate | Meaning |
|---------|:----:|---------|
| A | 41.17% | In 41% of (query, shop) pairs, Profile document won the MAX aggregation |
| B | 48.58% | Slightly higher because B review scores changed, shifting some wins |

Profile documents are a significant contributor to the semantic scores, but the A/B comparison is unaffected since they're shared.

## Production Anchor

| Metric | Value |
|--------|:-----:|
| B exact cosine (Profile+Review) vs B production Milvus overlap@3 | **0.8571** |
| B exact cosine (Profile+Review) vs B production Milvus overlap@5 | **0.7857** |

The Production Anchor overlap@3 (0.8571) is unchanged from v1 because the Milvus pre-filter already includes Profile documents. The overlap@5 improved slightly from 0.7714 (v1) to 0.7857 (v2), indicating that adding Profile to the exact side made the ranking closer to production.

## Decision

**Pre-registered rule**: Keep B (content+tags) only if Recall@3 ≥ +3pp AND NDCG@3/Top1/CVR not worse.

| Condition | Met? |
|-----------|:----:|
| Recall@3 ≥ +3pp (Δ = 0.00pp) | ❌ |
| NDCG@3 not worse | ✅ (+0.45pp) |
| Top1 not worse | ✅ (0.00pp) |
| CVR not worse | ✅ (0.00pp) |

**Result: Keep content-only (A).** Adding tags to review embedding text produces no measurable Recall or Top1 improvement, even with the full Profile + Review production pipeline.

## Key Findings

### 1. Tags Are Redundant with Content (Confirmed with Profile Pipeline)

The v2 experiment confirms the v1 finding: tags in the review embedding text produce **identical** Recall@3, Recall@5, and Top1 Accuracy. The only difference is a marginal NDCG@3 improvement of +0.45pp, which is within noise for 35 queries.

### 2. Profile Documents Are a Significant Signal Source

Profile-dominance rate of 41-49% means Profile documents contribute meaningfully to the MAX aggregation. This is expected — the Profile text (`菜系：日料。场景：约会,商务。环境：安静,景观`) is highly relevant to queries like "日料" or "安静的日料店".

### 3. Profile Does Not Change the A/B Conclusion

Since Profile embeddings are shared across A/B, they contribute equally to both variants. The A/B delta remains zero for Recall@3 and Top1.

### 4. Production Anchor Stable

The overlap@3 (0.8571) is the same as v1, confirming that the exact cosine ranking is a reasonable proxy for production ANN retrieval. The overlap@5 improved slightly (0.7857 vs 0.7714) because adding Profile to the exact side narrowed the gap with production.

## Comparison with v1 (Review-only)

| Aspect | v1 (Review-only) | v2 (Profile + Review) |
|--------|:-----------------:|:---------------------:|
| A Recall@3 | 0.7932 | 0.7932 |
| B Recall@3 | 0.7932 | 0.7932 |
| Anchor overlap@3 | 0.8571 | 0.8571 |
| Anchor overlap@5 | 0.7714 | 0.7857 |
| Profile-dominance | N/A | 41-49% |

The v2 results are identical to v1 for the comparison metrics. Adding Profile was necessary to align with the production pipeline, but it does not change the conclusion.

## Recommendations

1. **Keep content-only (A) for review embedding text.** Tags add no measurable recall improvement, even with the full Profile + Review production pipeline.

2. **The current production index (content+tags) is not harmful**, but it's unnecessary. Future re-indexing can use content-only review text without regression risk.

3. **Profile documents are working as expected** — they contribute meaningful semantic signal and should remain in the production index.

## Per-Case Results

See `retrieval_tags_ab_results.json` for full per-case details.