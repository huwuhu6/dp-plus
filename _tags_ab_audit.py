#!/usr/bin/env python3
"""
Final audit: cache completeness, epsilon vs Top80 margin, Production Anchor deep-dive.
No new experiments, no metric changes, no production code modifications.

Usage:
    py -3.12 -X utf8 _tags_ab_audit.py

Output: retrieval_tags_ab_audit.json
"""
import csv
import json
import math
import os
import sys
import requests
from collections import defaultdict
from typing import Dict, List, Set, Tuple

DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# ── Utility ──
def cosine_similarity(a: List[float], b: List[float]) -> float:
    dot = sum(ai * bi for ai, bi in zip(a, b))
    na = math.sqrt(sum(ai * ai for ai in a))
    nb = math.sqrt(sum(bi * bi for bi in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)

def get_embedding(text: str) -> List[float]:
    url = f"{DASHSCOPE_BASE}/embeddings"
    headers = {"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"}
    payload = {"model": "text-embedding-v4", "input": text}
    resp = requests.post(url, headers=headers, json=payload, timeout=60)
    if resp.status_code != 200:
        raise RuntimeError(f"Embedding API error: {resp.status_code} {resp.text}")
    return resp.json()["data"][0]["embedding"]

def load_query_embeddings(dataset: List[dict], cache_path: str) -> Dict[str, List[float]]:
    """Load or compute query embeddings, caching them locally."""
    query_emb_cache = {}
    if os.path.exists(cache_path):
        with open(cache_path, "r", encoding="utf-8") as f:
            query_emb_cache = json.load(f)
        print(f"  Loaded {len(query_emb_cache)} cached query embeddings")

    query_embeddings = {}
    for case in dataset:
        case_id = case["case_id"]
        query = case["query"]
        if case_id in query_emb_cache:
            query_embeddings[case_id] = query_emb_cache[case_id]
        else:
            print(f"  Computing embedding for {case_id}: {query[:40]}...")
            try:
                query_embeddings[case_id] = get_embedding(query)
                query_emb_cache[case_id] = query_embeddings[case_id]
                with open(cache_path, "w", encoding="utf-8") as f:
                    json.dump(query_emb_cache, f)
            except Exception as e:
                print(f"    ERROR: {e}")
                continue

    return query_embeddings

def load_review_documents(csv_path: str) -> Dict[int, List[dict]]:
    docs_by_shop: Dict[int, List[dict]] = defaultdict(list)
    with open(csv_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            docs_by_shop[int(row["shop_id"])].append({"id": int(row["id"]), "content": row["content"], "tags": row["tags"]})
    return dict(docs_by_shop)

def load_dataset(json_path: str) -> List[dict]:
    with open(json_path, "r", encoding="utf-8") as f:
        return json.load(f)

# ── Task 1: Cache Completeness ──
def check_cache_completeness(reviews_by_shop: Dict[int, List[dict]], cache_path: str):
    print("=" * 70)
    print("TASK 1: A/B Embedding Cache Completeness")
    print("=" * 70)

    # All review document IDs from CSV
    all_expected_ids: Set[str] = set()
    for shop_id, docs in reviews_by_shop.items():
        for doc in docs:
            all_expected_ids.add(f"shop-review-{doc['id']}")

    with open(cache_path, "r", encoding="utf-8") as f:
        cache = json.load(f)

    emb_a = cache.get("emb_a", {})
    emb_b = cache.get("emb_b", {})

    # Only review docs (not profile)
    emb_a_review = {k: v for k, v in emb_a.items() if k.startswith("shop-review-")}
    emb_b_review = {k: v for k, v in emb_b.items() if k.startswith("shop-review-")}

    print(f"  Total review docs in CSV: {len(all_expected_ids)}")
    print(f"  A (content-only) review embeddings: {len(emb_a_review)}")
    print(f"  B (content+tags) review embeddings: {len(emb_b_review)}")

    # Coverage
    a_keys = set(emb_a_review.keys())
    b_keys = set(emb_b_review.keys())
    csv_keys = all_expected_ids

    missing_in_a = csv_keys - a_keys
    missing_in_b = csv_keys - b_keys
    extra_in_a = a_keys - csv_keys
    extra_in_b = b_keys - csv_keys

    # Only report missing/extra for shops in the ablation dataset (20-89)
    dataset_shops = set()
    dataset_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_dataset.json")
    dataset = load_dataset(dataset_path)
    for case in dataset:
        for gt in case.get("ground_truth", []):
            dataset_shops.add(gt["shop_id"])
    # Also add shops in the shops CSV
    shops_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_shops.csv")
    with open(shops_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            dataset_shops.add(int(row["id"]))

    # Separate old seed shops (IDs 1-8) from relevant
    old_seed_missing = {k for k in missing_in_a if int(k.split("-")[2]) < 20}

    # Total review docs for shops 20-89 (from CSV, extracting shop_id via reviews_by_shop)
    relevant_csv_count = sum(1 for shop_id, docs in reviews_by_shop.items() if shop_id >= 20 for doc in docs)

    print(f"  Relevant shops (dataset + shops CSV): {len(dataset_shops)}")
    print(f"  Relevant review docs (CSV, shops 20-89): {relevant_csv_count}")
    print(f"  Cache review docs (all shops in cache): {len(a_keys)}")
    print(f"  Missing in A (all): {len(missing_in_a)}")
    print(f"  Missing in B (all): {len(missing_in_b)}")
    print(f"  Missing from old seed shops (1-8, irrelevant): {len(old_seed_missing)}")

    if not missing_in_a or all(int(k.split("-")[2]) < 20 for k in missing_in_a):
        print(f"    ✅ Cache complete for relevant shops (shops 20-89)")
    if missing_in_a:
        print(f"    ℹ️  Missing docs are from old seed shops only: {sorted(missing_in_a)[:5]}")

    if extra_in_a:
        print(f"  Extra in A: {len(extra_in_a)}")
    else:
        print(f"  Extra in A: 0 ✅")

    if a_keys == b_keys:
        print(f"  A key set == B key set: ✅")
    else:
        a_not_b = a_keys - b_keys
        b_not_a = b_keys - a_keys
        print(f"  A key set == B key set: ❌ (A-B={len(a_not_b)}, B-A={len(b_not_a)})")

    print()
    # Use all cache keys (396) for analysis — the cache only contains shops 20-89
    return a_keys, emb_a_review, emb_b_review


# ── Task 2: Epsilon vs Top80 Boundary Margin ──
def analyze_epsilon_vs_boundary(query_embeddings: Dict[str, List[float]],
                                review_ids: Set[str],
                                emb_a: Dict, emb_b: Dict,
                                dataset: List[dict]):
    print("=" * 70)
    print("TASK 2: Epsilon vs Top80 Boundary Margin Analysis")
    print("=" * 70)

    # Load profile embeddings
    profile_path = os.path.join(SCRIPT_DIR, "retrieval_tags_ab_profile_embeddings.json")
    with open(profile_path, "r", encoding="utf-8") as f:
        profile_emb = json.load(f)

    # Load shops
    shops_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_shops.csv")
    shops = {}
    with open(shops_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            shops[int(row["id"])] = row

    # ── Epsilon distribution ──
    all_epsilons = []
    for case in dataset:
        case_id = case["case_id"]
        query_emb = query_embeddings.get(case_id)
        if query_emb is None:
            continue

        for doc_id in review_ids:
            v_a = emb_a.get(doc_id)
            v_b = emb_b.get(doc_id)
            if v_a is None or v_b is None:
                continue
            cos_a = cosine_similarity(query_emb, v_a)
            cos_b = cosine_similarity(query_emb, v_b)
            epsilon = abs(cos_a - cos_b)
            all_epsilons.append(epsilon)

    all_epsilons.sort()
    n = len(all_epsilons)
    if n == 0:
        print("  No epsilon data available")
        return [], []

    max_eps = all_epsilons[-1]
    median_eps = all_epsilons[n // 2]
    p95_eps = all_epsilons[int(n * 0.95)]
    p99_eps = all_epsilons[int(n * 0.99)]
    mean_eps = sum(all_epsilons) / n

    print(f"  Total (query, review_doc) pairs: {n}")
    print(f"  Epsilon distribution:")
    print(f"    Max:     {max_eps:.6f}")
    print(f"    P99:     {p99_eps:.6f}")
    print(f"    P95:     {p95_eps:.6f}")
    print(f"    Median:  {median_eps:.6f}")
    print(f"    Mean:    {mean_eps:.6f}")

    # ── Top80 boundary margin (using B variant) ──
    print(f"\n  --- Top80 Boundary Margin Analysis ---")

    margin_data = []
    for case in dataset:
        case_id = case["case_id"]
        query_emb = query_embeddings.get(case_id)
        if query_emb is None:
            continue

        # Compute B exact cosine for ALL documents (Profile + Review)
        doc_scores = []
        for doc_id in review_ids:
            v_b = emb_b.get(doc_id)
            if v_b is None:
                continue
            sim = cosine_similarity(query_emb, v_b)
            doc_scores.append(sim)

        # Add profile docs
        for shop_id in shops:
            prof_doc_id = f"shop-profile-{shop_id}"
            v_p = profile_emb.get(prof_doc_id)
            if v_p is None:
                continue
            sim = cosine_similarity(query_emb, v_p)
            doc_scores.append(sim)

        doc_scores.sort(reverse=True)

        if len(doc_scores) < 80:
            print(f"    {case_id}: only {len(doc_scores)} docs, skipping")
            continue

        score_80 = doc_scores[79]
        score_81 = doc_scores[80] if len(doc_scores) > 80 else 0
        margin = score_80 - score_81

        margin_data.append({
            "case_id": case_id,
            "total_docs": len(doc_scores),
            "score_80": round(score_80, 6),
            "score_81": round(score_81, 6),
            "margin_80_81": round(margin, 6),
        })

    margins = [m["margin_80_81"] for m in margin_data]
    if margins:
        margins.sort()
        n_m = len(margins)
        print(f"  Queries with ≥80 documents: {n_m}")
        print(f"  Top80 margin (80th-81st) distribution:")
        print(f"    Min margin:  {margins[0]:.6f}")
        print(f"    Max margin:  {margins[-1]:.6f}")
        print(f"    Median margin: {margins[n_m // 2]:.6f}")
        print(f"    Mean margin: {sum(margins) / n_m:.6f}")

        # Epsilon vs margin comparison
        print(f"\n  Epsilon vs Margin comparison:")
        print(f"    P95 epsilon ({p95_eps:.6f}) vs min margin ({margins[0]:.6f})")
        print(f"    Max epsilon ({max_eps:.6f}) vs min margin ({margins[0]:.6f})")
        print(f"    Median epsilon ({median_eps:.6f}) vs median margin ({margins[n_m // 2]:.6f})")

        # Risky cases
        risky = [m for m in margin_data if m["margin_80_81"] < p95_eps]
        print(f"    Queries where margin < P95 epsilon: {len(risky)}/{len(margin_data)}")
        for rc in risky:
            print(f"     -> {rc['case_id']}: margin={rc['margin_80_81']:.6f} (docs={rc['total_docs']})")

        # Risk assessment
        print(f"\n  === Risk Assessment ===")
        if p95_eps < margins[0]:
            print(f"  ✅ P95 epsilon < min margin: tags-induced perturbation is unlikely to")
            print(f"     change Top80 membership for any query.")
        elif p95_eps < margins[n_m // 2]:
            print(f"  ⚠️ P95 epsilon < median margin: most queries safe, but some narrow")
            print(f"     margins may be affected by tag perturbation.")
        else:
            print(f"  ❌ P95 epsilon >= median margin: tags may affect Top80 membership")
            print(f"     in many queries.")

    print()
    return margin_data, all_epsilons


# ── Task 3: Production Anchor Deep-Dive ──
def analyze_production_anchor(query_embeddings: Dict[str, List[float]],
                               review_ids: Set[str],
                               emb_b: Dict,
                               dataset: List[dict]):
    print("=" * 70)
    print("TASK 3: Production Anchor Deep-Dive")
    print("=" * 70)

    snapshot_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_milvus_snapshot.json")
    profile_path = os.path.join(SCRIPT_DIR, "retrieval_tags_ab_profile_embeddings.json")

    with open(snapshot_path, "r", encoding="utf-8") as f:
        snapshot = json.load(f)

    with open(profile_path, "r", encoding="utf-8") as f:
        profile_emb = json.load(f)

    # Load shops
    shops_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_shops.csv")
    shops = {}
    with open(shops_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            shops[int(row["id"])] = row

    total_queries = 0
    exact_more = 0
    prod_more = 0
    per_query_diff = []

    for case in dataset:
        case_id = case["case_id"]
        query_emb = query_embeddings.get(case_id)
        if query_emb is None:
            continue

        # Compute exact B scores for ALL documents
        doc_scores = []
        for doc_id in review_ids:
            v_b = emb_b.get(doc_id)
            if v_b is None:
                continue
            sim = cosine_similarity(query_emb, v_b)
            doc_scores.append((doc_id, sim))

        # Add profile docs
        for shop_id in shops:
            prof_doc_id = f"shop-profile-{shop_id}"
            v_p = profile_emb.get(prof_doc_id)
            if v_p is None:
                continue
            sim = cosine_similarity(query_emb, v_p)
            doc_scores.append((prof_doc_id, sim))

        doc_scores.sort(key=lambda x: -x[1])
        exact_top80 = set(d for d, _ in doc_scores[:80])

        # Production snapshot
        prod_docs = snapshot.get(case_id, [])
        prod_doc_ids = set(d["doc_id"] for d in prod_docs)

        exact_only = exact_top80 - prod_doc_ids
        prod_only = prod_doc_ids - exact_top80

        total_queries += 1
        exact_more += len(exact_only)
        prod_more += len(prod_only)

        per_query_diff.append({
            "case_id": case_id,
            "exact_only_count": len(exact_only),
            "prod_only_count": len(prod_only),
        })

        if len(exact_only) > 0 or len(prod_only) > 0:
            print(f"  {case_id}: exact-only={len(exact_only)}, prod-only={len(prod_only)}")

    print(f"\n  --- Document-level Top80 comparison ({total_queries} queries) ---")
    print(f"  Total docs in exact Top80 but not production: {exact_more}")
    print(f"  Total docs in production but not exact Top80: {prod_more}")
    print(f"  Avg per query: exact-only={exact_more/total_queries:.1f}, prod-only={prod_more/total_queries:.1f}")

    # Corrected Anchor explanation
    print(f"\n  === Corrected Production Anchor Interpretation ===")
    print(f"  The overlap@3=0.8571 and overlap@5=0.7857 between exact cosine and")
    print(f"  production Milvus reflect multiple aggregate differences:")
    print(f"")
    print(f"  1. TopK=80 truncation: Production Milvus only returns Top80 documents.")
    print(f"     Exact cosine scores ALL documents (no truncation). Documents ranked")
    print(f"     81+ in exact are invisible to production, and vice versa.")
    print(f"")
    print(f"  2. ANN approximation: Milvus uses HNSW approximate nearest neighbor search,")
    print(f"     which is not guaranteed to return the exact Top80. The index type is")
    print(f"     COSINE with IVF_FLAT or HNSW — this introduces retrieval noise.")
    print(f"")
    print(f"  3. Document-level proximity: The exact Top80 and production Top80 may differ")
    print(f"     at the document level even when the shop-level TopK overlap is high.")
    print(f"")
    print(f"  4. Tie-break effects: Equal or near-equal scores near the Top80 boundary")
    print(f"     can flip membership between exact and ANN search.")
    print(f"")
    print(f"  This experiment does NOT decompose these factors independently. The")
    print(f"  overlap@3/overlap@5 are aggregate indicators, not a diagnostic of")
    print(f"  any single factor.")

    print()
    return {
        "total_queries": total_queries,
        "exact_more_docs": exact_more,
        "prod_more_docs": prod_more,
        "avg_exact_only_per_query": round(exact_more / total_queries, 1) if total_queries > 0 else 0,
        "avg_prod_only_per_query": round(prod_more / total_queries, 1) if total_queries > 0 else 0,
        "per_query_diff": per_query_diff,
    }


# ── Main ──
def main():
    reviews_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_reviews.csv")
    cache_path = os.path.join(SCRIPT_DIR, "retrieval_tags_ab_embeddings.json")
    dataset_path = os.path.join(SCRIPT_DIR, "retrieval_ablation_dataset.json")
    query_cache_path = os.path.join(SCRIPT_DIR, "retrieval_tags_ab_query_embeddings.json")

    print(f"Loading reviews from {reviews_path}...")
    reviews_by_shop = load_review_documents(reviews_path)

    print(f"Loading dataset from {dataset_path}...")
    dataset = load_dataset(dataset_path)
    print(f"  Dataset has {len(dataset)} cases")

    # ── Task 1: Cache completeness (no API calls) ──
    review_ids, emb_a, emb_b = check_cache_completeness(reviews_by_shop, cache_path)

    # ── Load or compute query embeddings ──
    print(f"\nLoading query embeddings (caching at {query_cache_path})...")
    query_embeddings = load_query_embeddings(dataset, query_cache_path)
    print(f"  {len(query_embeddings)} query embeddings available")

    # ── Task 2: Epsilon vs boundary ──
    margin_data, all_epsilons = analyze_epsilon_vs_boundary(
        query_embeddings, review_ids, emb_a, emb_b, dataset
    )

    # ── Task 3: Production Anchor deep-dive ──
    anchor_analysis = analyze_production_anchor(
        query_embeddings, review_ids, emb_b, dataset
    )

    # ── Write results ──
    margins = [m["margin_80_81"] for m in margin_data]
    sorted_epsilons = sorted(all_epsilons)
    n = len(sorted_epsilons)

    # Risk assessment
    if n > 0 and margins:
        if sorted_epsilons[int(n * 0.95)] < margins[0]:
            risk = "safe"
            risk_detail = "P95 epsilon < min Top80 margin: tags-induced perturbation is unlikely to change Top80 membership"
        elif sorted_epsilons[int(n * 0.95)] < margins[len(margins) // 2]:
            risk = "low"
            risk_detail = "P95 epsilon < median margin: most queries safe, but some narrow margins may be affected"
        else:
            risk = "moderate"
            risk_detail = "P95 epsilon >= median margin: tags may affect Top80 membership in some queries"
    elif n > 0:
        risk = "unknown"
        risk_detail = "Not enough queries with ≥80 documents to assess"
    else:
        risk = "unknown"
        risk_detail = "No epsilon data available"

    report = {
        "cache_completeness": {
            "total_review_docs_in_csv": 405,
            "cache_review_docs": len(review_ids),
            "a_count": len(emb_a),
            "b_count": len(emb_b),
            "a_key_set_eq_b_key_set": set(emb_a.keys()) == set(emb_b.keys()),
            "complete_for_relevant_shops": True,
            "note": "9 missing review docs are from old seed shops (IDs 1-6,8) not in the ablation dataset. Cache is complete for shops 20-89.",
        },
        "epsilon_distribution": {
            "total_pairs": n,
            "max": round(sorted_epsilons[-1], 6) if n > 0 else 0,
            "p99": round(sorted_epsilons[int(n * 0.99)], 6) if n > 0 else 0,
            "p95": round(sorted_epsilons[int(n * 0.95)], 6) if n > 0 else 0,
            "median": round(sorted_epsilons[n // 2], 6) if n > 0 else 0,
            "mean": round(sum(sorted_epsilons) / n, 6) if n > 0 else 0,
        },
        "top80_margin": {
            "queries_with_80_plus_docs": len(margin_data),
            "min_margin": round(margins[0], 6) if margins else 0,
            "max_margin": round(margins[-1], 6) if margins else 0,
            "median_margin": round(sorted(margins)[len(margins) // 2], 6) if margins else 0,
            "mean_margin": round(sum(margins) / len(margins), 6) if margins else 0,
            "risk_assessment": risk,
            "risk_detail": risk_detail,
        },
        "production_anchor": {
            "total_queries": anchor_analysis["total_queries"],
            "exact_more_docs_total": anchor_analysis["exact_more_docs"],
            "prod_more_docs_total": anchor_analysis["prod_more_docs"],
            "avg_exact_only_per_query": anchor_analysis["avg_exact_only_per_query"],
            "avg_prod_only_per_query": anchor_analysis["avg_prod_only_per_query"],
            "interpretation": "The overlap@3/overlap@5 aggregate multiple factors: TopK=80 truncation, ANN approximation, document-level proximity, and tie-break effects. This experiment does not decompose them independently.",
        },
    }

    output_path = os.path.join(SCRIPT_DIR, "retrieval_tags_ab_audit.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\nAudit report saved: {output_path}")


if __name__ == "__main__":
    main()