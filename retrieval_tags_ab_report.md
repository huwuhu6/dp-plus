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

---

## Final Audit (2026-08-31)

### 1. Cache Completeness

| Check | Result |
|-------|:------:|
| Total review docs in CSV | 405 |
| A (content-only) cache entries | 396 |
| B (content+tags) cache entries | 396 |
| A key set == B key set | ✅ |
| Missing from relevant shops (20-89) | **0** |
| Missing from old seed shops (1-6, 8) | 9 (irrelevant) |

**Conclusion**: Cache is complete for the ablation dataset. The 9 missing entries are from old seed shops not included in the ablation dataset.

### 2. Epsilon vs Top80 Boundary Margin Analysis

Epsilon = |cos_A - cos_B| for each (query, review_document) pair. Measures how much adding tags changes the cosine similarity score.

| Statistic | Value |
|-----------|:-----:|
| Total (query, doc) pairs | 13,860 |
| Max epsilon | 0.0399 |
| P99 epsilon | 0.0203 |
| P95 epsilon | 0.0142 |
| Median epsilon | 0.0044 |
| Mean epsilon | 0.0055 |

**Top80 boundary margin** (80th vs 81st rank, all 462 documents across 35 queries):

| Statistic | Value |
|-----------|:-----:|
| Queries with ≥80 docs | 35/35 |
| Min margin | 0.000043 |
| Median margin | 0.000474 |
| Max margin | 0.002552 |

**Comparison**: P95 epsilon (0.0142) ≈ 30× median margin (0.00047). The tags-induced perturbation is **larger than the Top80 boundary margin for all 35 queries**.

**However**, this does not contradict the null A/B result. The epsilon is a signed difference — adding tags raises some document scores and lowers others. The MAX aggregation across Profile + Review documents smooths these individual changes. Since Profile embeddings are identical across A/B, the shop-level ranking is largely preserved.

### 3. Production Anchor Deep-Dive

Document-level Top80 comparison between exact cosine and production Milvus snapshot:

| Metric | Value |
|--------|:-----:|
| Queries analyzed | 35 |
| Exact-only docs (avg/query) | 59.7 |
| Prod-only docs (avg/query) | 7.5 |

The document-level difference is large (avg 59.7 docs in exact Top80 but not in production), yet the shop-level overlap@3 remains 0.8571. This confirms that the **shop-level MAX aggregation masks document-level differences**: as long as at least one document per shop is in both Top80s, the shop-level overlap is preserved.

### 4. Corrected Production Anchor Interpretation

The overlap@3=0.8571 and overlap@5=0.7857 are **aggregate indicators** reflecting multiple factors:

1. **TopK=80 truncation**: Production Milvus only returns Top80 documents. Exact cosine scores ALL documents. Documents ranked 81+ in exact are invisible to production, and vice versa.
2. **ANN approximation**: Milvus uses HNSW approximate search, which is not guaranteed to return the exact Top80.
3. **Document-level proximity**: The exact Top80 and production Top80 may differ at the document level even when the shop-level overlap is high.
4. **Tie-break effects**: Near-equal scores near the Top80 boundary can flip membership between exact and ANN search.

**These factors are not decomposed independently.** The overlap@3/overlap@5 are sufficient for the A/B comparison (both variants see the same production anchor), but not a diagnostic of any single factor.

### 5. Final Qualification

| Question | Answer |
|----------|:------:|
| Is the A/B conclusion valid? | **Yes.** Tags are redundant with review content. No recall or Top1 improvement. |
| Is the cache complete? | **Yes.** 396/396 entries for relevant shops. A==B key sets. |
| Do tags perturb ranking? | **Yes, at the document level.** But the effect washes out at the shop level due to MAX aggregation and shared Profile embeddings. |
| Is the production anchor reliable? | **As an aggregate indicator, yes.** It is not a single-factor diagnostic, but it is stable across variants. |

**Final verdict**: Keep content-only (A) for review embedding text. No engineering action required on the current production index (content+tags). Switch to content-only at the next natural re-indexing event.