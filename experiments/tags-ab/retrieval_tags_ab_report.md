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

### 3. Shop-level Ranking Margin vs Tags Perturbation

Examines whether the maximum possible tags-induced perturbation at the semantic score level (18 × max epsilon = 0.7175) could cross the Top3 shop boundary in the final deterministic ranking.

| Metric | Value |
|--------|:-----:|
| Queries with ≥4 shops for margin analysis | 22/35 |
| Queries with <4 shops (insufficient) | 13/35 |
| Min shop margin (score@3 - score@4) | 0.0877 |
| Median shop margin | 0.3129 |
| Max shop margin | 13.3509 |
| 18 × max epsilon | 0.7175 |
| Queries with margin > 0.7175 | 8/22 |

**Result**: Min shop margin (0.0877) ≤ 18×max epsilon (0.7175). **Current data cannot establish a strict ranking robustness certificate.** Only 8/22 queries have margin > the maximum possible perturbation.

However, this is a conservative bound. The epsilon is the *maximum possible* signed perturbation, not the *actual* perturbation for any given document. The actual A/B experiment showed zero Recall@3 and Top1 change, indicating that the perturbation direction happened to not cross the ranking boundary in practice.

**No tie-break events detected** (no queries with equal scores at position 3 or 4+).

### 4. Fingerprint / Text Contract Reconciliation

| Check | Result |
|-------|:------:|
| Fingerprint stored in Milvus metadata? | **No** |
| Snapshot metadata fields | doc_id, shop_id, doc_type, score |
| Factory metadata fields | shopId, documentType, reviewId, sourceType, sourceRevision, **documentFingerprint** |

**`documentFingerprint` is NOT persisted in Milvus metadata.** `SemanticShopDocumentFactory.document()` adds it to the metadata map, but Spring AI's `MilvusVectorStore` does not store it in the metadata JSON field. Fingerprint-based contract verification is not possible from the existing Milvus snapshot.

**Text contract reconstruction**: Production factory text templates (`SemanticShopDocumentFactory.profileDocument()`, `reviewDocument()`) match the current data sources (CSV exports). No text/metadata drift detected between current data sources and the production factory contract.

### 5. Corrected Production Anchor Attribution

The difference between exact cosine Top80 and production Milvus Top80 is attributed to the following factors, **not decomposed independently**:

| Factor | Role |
|--------|:----:|
| **Filtered ANN retrieval behavior** | **Primary factor.** Production uses Milvus ANN (HNSW/COSINE) with pre-filter. Exact cosine evaluates ALL documents exhaustively. ANN approximation does not guarantee exact Top80. |
| **Embedding source / contract** | **Cannot verify.** Fingerprint not stored in Milvus metadata. Text contract reconstruction matches current source, but cannot prove production vectors were generated from the same text at index time. |
| **Boundary / tie-break effects** | **Minor factor.** Near-equal similarity near the Top80 boundary can flip membership. |
| **Shop-level MAX masking** | **Explains shop-level overlap.** Document-level differences (avg 59.7 exact-only + 7.5 prod-only per query) do not propagate to shop-level Top3 (overlap@3=0.8571) because MAX aggregation across Profile + Review preserves shop overlap. |

**The overlap@3/overlap@5 are aggregate indicators, not a diagnostic of any single factor.**

### 6. Final Qualification

| Question | Answer |
|----------|:------:|
| Is the A/B conclusion valid? | **Yes.** Tags are redundant with review content. No recall or Top1 improvement. |
| Is the cache complete? | **Yes.** 396/396 entries for relevant shops. A==B key sets. |
| Do tags perturb ranking? | **Yes, at the document level.** But the effect washes out at the shop level. |
| Is the production anchor reliable? | **As an aggregate indicator, yes.** Not a single-factor diagnostic, but stable across variants. |
| Can we prove ranking robustness? | **No.** Min shop margin (0.0877) ≤ 18×max epsilon (0.7175). No strict certificate possible. |
| Can we verify fingerprint? | **No.** Fingerprint not stored in Milvus metadata. |

### 7. Final Semantic Boundary

**Proven**:
- `A`: Tags change the Review embedding representation.
- `B`: In the Profile + Review + MAX + threshold + ranking exact semantic pipeline, tags produce no Recall@3 or Top1 improvement above the pre-registered threshold.

**Not directly proven**:
- `C`: content-only re-indexed into Production Milvus ANN will produce identical Top80 / ranking to current content+tags index.

**Decision**: content-only is adopted as the formal text contract for future Review embedding. The current production index (content+tags) remains unchanged. A production qualification runbook is defined for the next natural re-indexing event.

### 8. Production Re-index Runbook

**Current index**: content+tags (no action required).

**Next natural full Review re-index**:

1. Switch Review embedding text to content-only (A variant) in `SemanticShopDocumentFactory.reviewDocument()`.
2. Rebuild Review embeddings using the existing vector sync/reconciliation mechanism.
3. Run the existing retrieval evaluation harness on the frozen 35-query ablation dataset:

   **Baseline** (current V_Sem production):
   ```
   Recall@3 = 0.7614
   NDCG@3   = 0.9558
   Top1     = 0.9355
   CVR      = 0
   ```

4. Compare content-only ANN results against baseline:

   **Pre-registered qualification rule**:
   ```
   Recall@3 >= baseline - 2pp
   AND CVR = 0
   AND no new SEMANTIC_THRESHOLD_ERROR mode
   ```

5. Monitor:
   - Allowed set shops with zero Top80 documents (should not increase relative to B baseline)
   - New `SEMANTIC_THRESHOLD_ERROR` emergence
   - Shop-level ranking distribution changes

6. **If qualification passes**: Accept content-only production ANN behavior.
7. **If qualification fails**: Revert Review embedding text to content+tags, record as Known Limitation, do not create a second experiment cycle.

**Note**: This runbook is for the next natural re-indexing event. Do not create a temporary Milvus collection, do not modify `SemanticShopDocumentFactory`, do not trigger a production re-index for this purpose alone.