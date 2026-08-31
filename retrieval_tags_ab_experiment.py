#!/usr/bin/env python3
"""
Review Embedding Content-only vs Content+Tags A/B Experiment.

Two variants:
  A: text = "商户：{name}。评价证据：{content}。标签："          (content-only, tags stripped)
  B: text = "商户：{name}。评价证据：{content}。标签：{tags}"   (content+tags, matches current production)

Method: exact cosine over allowed set (NOT ANN). This eliminates ANN retrieval noise
and isolates the embedding text representation variable.

Pre-registered decision rule:
  Keep B (content+tags) only if Recall@3 ≥ +3pp AND NDCG@3/Top1/CVR not worse.
  If condition not met, use A (content-only).

Output: retrieval_tags_ab_results.json, retrieval_tags_ab_report.md

Production Anchor: B exact cosine vs B production Milvus pre-filter (from existing snapshot).
"""

import csv
import json
import math
import os
import sys
import time
import requests
from collections import defaultdict
from dataclasses import dataclass, asdict
from typing import Dict, List, Optional, Set, Tuple

DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
MILVUS_HOST = "http://127.0.0.1:19530"

# ── Cuisine Canonicalizer ──
CUISINE_MAP = {
    "日料": "日料", "日本料理": "日料", "日式料理": "日料", "日本菜": "日料", "寿司": "日料",
    "烧烤": "烧烤", "烤肉": "烧烤",
    "西餐": "西餐", "牛排": "西餐",
    "港式": "港式", "茶餐厅": "港式", "港式茶餐厅": "港式",
    "火锅": "火锅",
}

def canonicalize_cuisine(cuisine: str) -> str:
    if not cuisine or not cuisine.strip():
        return ""
    return CUISINE_MAP.get(cuisine.strip(), cuisine.strip())

def matches_cuisine(profile_cuisine: str, requested_cuisine: str) -> bool:
    if not profile_cuisine or not requested_cuisine:
        return False
    req_canon = canonicalize_cuisine(requested_cuisine)
    if not req_canon:
        return False
    for token in profile_cuisine.split(","):
        if canonicalize_cuisine(token.strip()) == req_canon:
            return True
    return False

# ── Haversine ──
def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

# ── Open hours ──
def minute_of_day(time_str: str) -> int:
    parts = time_str.strip().split(":")
    return int(parts[0]) * 60 + int(parts[1])

def is_open_at(open_hours: str, arrival_time: str) -> bool:
    if not arrival_time or not open_hours or not open_hours.strip():
        return True
    try:
        target = minute_of_day(arrival_time)
        ranges = open_hours.replace(" ", "").split(",")
        for rng in ranges:
            parts = rng.split("-")
            if len(parts) != 2:
                continue
            begin = minute_of_day(parts[0])
            end = minute_of_day(parts[1])
            if end < begin:
                if target >= begin or target <= end:
                    return True
            else:
                if begin <= target <= end:
                    return True
        return False
    except Exception:
        return True


@dataclass
class Shop:
    id: int
    name: str
    province: str
    city: str
    x: float
    y: float
    avg_price: Optional[int]
    score: int
    open_hours: str

@dataclass
class ShopProfile:
    shop_id: int
    cuisine: str
    scene_tags: str
    ambience_tags: str
    queue_level: str

@dataclass
class ReviewDocument:
    id: int
    shop_id: int
    content: str
    tags: str

@dataclass
class GtShop:
    shop_id: int
    relevance: int
    note: str

@dataclass
class AblationCase:
    case_id: str
    query: str
    latitude: float
    longitude: float
    city: str
    ground_truth: List[GtShop]
    expected_constraints: dict
    scenario: str
    notes: str


# ── Loaders ──
def load_shops(csv_path: str) -> Dict[int, Shop]:
    shops = {}
    with open(csv_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            sid = int(row["id"])
            shops[sid] = Shop(
                id=sid,
                name=row["name"],
                province=row.get("province", ""),
                city=row.get("city", ""),
                x=float(row["x"]),
                y=float(row["y"]),
                avg_price=int(row["avg_price"]) if row.get("avg_price") else None,
                score=int(row["score"]) if row.get("score") else 0,
                open_hours=row.get("open_hours", ""),
            )
    return shops

def load_profiles(csv_path: str) -> Dict[int, ShopProfile]:
    profiles = {}
    with open(csv_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            sid = int(row["shop_id"])
            profiles[sid] = ShopProfile(
                shop_id=sid,
                cuisine=row.get("cuisine", ""),
                scene_tags=row.get("scene_tags", ""),
                ambience_tags=row.get("ambience_tags", ""),
                queue_level=row.get("queue_level", ""),
            )
    return profiles

def load_dataset(json_path: str) -> List[AblationCase]:
    with open(json_path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    cases = []
    for item in raw:
        gts = [GtShop(**g) for g in item.get("ground_truth", [])]
        cases.append(AblationCase(
            case_id=item["case_id"],
            query=item["query"],
            latitude=item["latitude"],
            longitude=item["longitude"],
            city=item["city"],
            ground_truth=gts,
            expected_constraints=item.get("expected_constraints", {}),
            scenario=item.get("scenario", ""),
            notes=item.get("notes", ""),
        ))
    return cases

def load_review_documents(csv_path: str) -> Dict[int, List[ReviewDocument]]:
    """Load review documents grouped by shop_id."""
    docs_by_shop: Dict[int, List[ReviewDocument]] = defaultdict(list)
    with open(csv_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            doc = ReviewDocument(
                id=int(row["id"]),
                shop_id=int(row["shop_id"]),
                content=row["content"],
                tags=row.get("tags", ""),
            )
            docs_by_shop[doc.shop_id].append(doc)
    return dict(docs_by_shop)


# ── Hard Filter ──
def matches_hard_constraints(shop: Shop, profile: Optional[ShopProfile], constraints: dict, lat: float, lon: float) -> bool:
    budget = constraints.get("budgetPerPerson", -1)
    if budget > 0 and (shop.avg_price is None or shop.avg_price > budget):
        return False
    cuisine = constraints.get("cuisine", "")
    if cuisine and (profile is None or not matches_cuisine(profile.cuisine, cuisine)):
        return False
    radius = constraints.get("radiusKm", -1)
    if radius > 0:
        d = distance_km(lat, lon, shop.y, shop.x)
        if d > radius:
            return False
    arrival = constraints.get("arrivalTime", "")
    if arrival and not is_open_at(shop.open_hours, arrival):
        return False
    return True

def sql_filter_shops(shops: Dict[int, Shop], city: str) -> Dict[int, Shop]:
    if not city:
        return dict(shops)
    city_norm = city.replace("市", "").strip()
    result = {}
    for sid, shop in shops.items():
        shop_city = shop.city.replace("市", "").strip() if shop.city else ""
        if shop_city == city_norm:
            result[sid] = shop
    return result

def compute_hard_matched_ids(city: str, constraints: dict, lat: float, lon: float, shops: Dict[int, Shop], profiles: Dict[int, ShopProfile]) -> Set[int]:
    sql_filtered = sql_filter_shops(shops, city)
    hard_matched = set()
    for sid, shop in sql_filtered.items():
        profile = profiles.get(sid)
        if matches_hard_constraints(shop, profile, constraints, lat, lon):
            hard_matched.add(sid)
    return hard_matched


# ── Embedding ──
def get_embedding(text: str) -> List[float]:
    url = f"{DASHSCOPE_BASE}/embeddings"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {"model": "text-embedding-v4", "input": text}
    resp = requests.post(url, headers=headers, json=payload, timeout=60)
    if resp.status_code != 200:
        raise RuntimeError(f"Embedding API error: {resp.status_code} {resp.text}")
    return resp.json()["data"][0]["embedding"]

def cosine_similarity(a: List[float], b: List[float]) -> float:
    dot = sum(ai * bi for ai, bi in zip(a, b))
    na = math.sqrt(sum(ai * ai for ai in a))
    nb = math.sqrt(sum(bi * bi for bi in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


# ── Build A/B text and pre-compute embeddings ──
def build_ab_texts(shop_name: str, review: ReviewDocument) -> Tuple[str, str]:
    """Return (text_a, text_b) for a review document."""
    text_a = f"商户：{shop_name}。评价证据：{review.content}。标签："
    text_b = f"商户：{shop_name}。评价证据：{review.content}。标签：{review.tags}"
    return text_a, text_b

def precompute_embeddings(
    shops: Dict[int, Shop],
    reviews_by_shop: Dict[int, List[ReviewDocument]],
    cache_path: str,
) -> Tuple[Dict[str, List[float]], Dict[str, List[float]]]:
    """Pre-compute A and B embeddings for all review documents.

    Returns:
        emb_a: {doc_id: embedding}  (content-only)
        emb_b: {doc_id: embedding}  (content+tags)
    """
    # Load cache if exists
    emb_a: Dict[str, List[float]] = {}
    emb_b: Dict[str, List[float]] = {}
    if os.path.exists(cache_path):
        with open(cache_path, "r", encoding="utf-8") as f:
            cache = json.load(f)
        emb_a = {k: v for k, v in cache.get("emb_a", {}).items()}
        emb_b = {k: v for k, v in cache.get("emb_b", {}).items()}
        print(f"Loaded {len(emb_a)} A embeddings, {len(emb_b)} B embeddings from cache")

    # Collect all review docs that need embedding
    pending = []
    for shop_id, docs in reviews_by_shop.items():
        shop = shops.get(shop_id)
        if not shop:
            continue
        for doc in docs:
            doc_id = f"shop-review-{doc.id}"
            if doc_id not in emb_a or doc_id not in emb_b:
                text_a, text_b = build_ab_texts(shop.name, doc)
                pending.append((doc_id, text_a, text_b))

    if not pending:
        print("All embeddings already cached")
        return emb_a, emb_b

    print(f"Computing {len(pending)} pairs of A/B embeddings...")

    for i, (doc_id, text_a, text_b) in enumerate(pending):
        try:
            if i > 0 and i % 10 == 0:
                print(f"  [{i}/{len(pending)}]...", end=" ", flush=True)
                # Save cache periodically
                _save_embedding_cache(cache_path, emb_a, emb_b)
                print("cached")

            emb_a[doc_id] = get_embedding(text_a)
            # Rate limit: avoid burst
            time.sleep(0.1)
            emb_b[doc_id] = get_embedding(text_b)
            time.sleep(0.1)

            if i % 50 == 0:
                print(f"  [{i}/{len(pending)}] {doc_id} done")

        except Exception as e:
            print(f"  ERROR computing {doc_id}: {e}")
            continue

    # Final save
    _save_embedding_cache(cache_path, emb_a, emb_b)
    print(f"Saved {len(emb_a)} A + {len(emb_b)} B embeddings to cache")
    return emb_a, emb_b

def _save_embedding_cache(cache_path: str, emb_a: Dict, emb_b: Dict):
    """Save embedding cache to disk."""
    temp_path = cache_path + ".tmp"
    with open(temp_path, "w", encoding="utf-8") as f:
        json.dump({"emb_a": emb_a, "emb_b": emb_b}, f, ensure_ascii=False)
    os.replace(temp_path, cache_path)


# ── Exact cosine scoring per shop ──
def compute_semantic_scores_exact(
    query_emb: List[float],
    allowed_ids: Set[int],
    reviews_by_shop: Dict[int, List[ReviewDocument]],
    embeddings: Dict[str, List[float]],
    shops: Dict[int, Shop],
) -> Dict[int, float]:
    """Compute per-shop semantic score using exact cosine similarity.

    For each allowed shop, computes cosine similarity between query embedding and
    each of the shop's review document embeddings, then takes the MAX (same as
    production scoreByShopId.merge(shopId, score, Math::max)).
    """
    scores: Dict[int, float] = {}
    for shop_id in allowed_ids:
        docs = reviews_by_shop.get(shop_id, [])
        max_score = 0.0
        for doc in docs:
            doc_id = f"shop-review-{doc.id}"
            doc_emb = embeddings.get(doc_id)
            if doc_emb is None:
                continue
            sim = cosine_similarity(query_emb, doc_emb)
            if sim > max_score:
                max_score = sim
        if max_score > 0:
            scores[shop_id] = max_score
    return scores


# ── Ranking (same formula as retrieval_ablation_experiment.py) ──
def compute_rank_score(
    shop: Shop,
    profile: Optional[ShopProfile],
    constraints: dict,
    semantic_score: Optional[float],
    lat: float,
    lon: float,
    evidence_count: int,
) -> float:
    """Production toRecommendation() ranking formula."""
    budget = constraints.get("budgetPerPerson", -1)
    radius = constraints.get("radiusKm", -1)
    occasion = constraints.get("occasion", "")
    quiet = constraints.get("quiet", False)
    avoid_queue = constraints.get("avoidQueue", False)
    semantic_weight = 18.0

    # Rating: max 20
    rating = shop.score / 10.0 / 5.0 * 20.0 if shop.score > 0 else 0.0

    # Budget: max 20
    budget_score = 0.0
    if budget > 0 and shop.avg_price and shop.avg_price > 0:
        ratio = shop.avg_price / budget
        budget_score = 20.0 * (1.0 - ratio * 0.3)
    budget_score = max(0.0, budget_score)

    # Occasion: +12
    occasion_score = 12.0 if (occasion and profile and contains_tag(profile.scene_tags, occasion)) else 0.0

    # Distance: max 20
    distance_score = 0.0
    if radius > 0:
        d = distance_km(lat, lon, shop.y, shop.x)
        distance_score = max(0.0, 20.0 * (1.0 - d / radius))

    # Quiet: +12
    quiet_score = 12.0 if (quiet and profile and contains_tag(profile.ambience_tags, "安静")) else 0.0

    # Avoid queue: +8
    queue_score = 8.0 if (avoid_queue and profile and profile.queue_level == "LOW") else 0.0

    # Evidence: +3 per doc, max 2 docs = +6
    evidence_score = min(6.0, evidence_count * 3.0)

    # Base score (without semantic)
    base = rating + budget_score + occasion_score + distance_score + quiet_score + queue_score + evidence_score

    # Semantic
    if semantic_score is not None:
        base += semantic_score * semantic_weight

    return min(100.0, base)

def contains_tag(tag_str: str, target: str) -> bool:
    if not tag_str or not target:
        return False
    for tag in tag_str.split(","):
        if tag.strip() == target:
            return True
    return False


# ── Metrics ──
def recall_at_k(gt_ids: List[int], ranked_ids: List[int], k: int) -> float:
    top_k = set(ranked_ids[:k])
    hits = sum(1 for gt in gt_ids if gt in top_k)
    return hits / len(gt_ids) if gt_ids else 0.0

def dcg(relevance_scores: List[int], k: int) -> float:
    scores = relevance_scores[:k]
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(scores))

def ndcg_at_k(gt_with_grades: List[Tuple[int, int]], ranked_ids: List[int], k: int) -> float:
    gt_map = {sid: grade for sid, grade in gt_with_grades}
    relevance = [gt_map.get(sid, 0) for sid in ranked_ids[:k]]
    dcg_k = dcg(relevance, k)
    ideal = sorted(gt_map.values(), reverse=True)[:k]
    idcg_k = dcg(ideal, k)
    return dcg_k / idcg_k if idcg_k > 0 else 0.0

def top1_accuracy(gt_ids: List[int], ranked_ids: List[int]) -> float:
    if not gt_ids or not ranked_ids:
        return 0.0
    return 1.0 if ranked_ids[0] in gt_ids else 0.0

def constraint_violation_rate(ranked_ids: List[int], shops: Dict[int, Shop], constraints: dict, lat: float, lon: float) -> float:
    if not ranked_ids:
        return 0.0
    violations = 0
    budget = constraints.get("budgetPerPerson", -1)
    radius = constraints.get("radiusKm", -1)
    for sid in ranked_ids:
        shop = shops.get(sid)
        if not shop:
            continue
        if budget > 0 and shop.avg_price and shop.avg_price > budget:
            violations += 1
        elif radius > 0:
            d = distance_km(lat, lon, shop.y, shop.x)
            if d > radius:
                violations += 1
    return violations / len(ranked_ids)


# ── Failure Attribution ──
def diagnose_failure_layer(
    gt_shop_id: int,
    constraints: dict,
    hard_matched: Set[int],
    semantic_scores: Dict[int, float],
    ranked_ids: List[int],
    k: int,
) -> Tuple[str, str]:
    if gt_shop_id not in hard_matched:
        return "HARD_FILTER_ERROR", "GT not in hard-matched set"
    if gt_shop_id not in semantic_scores:
        return "SEMANTIC_RETRIEVAL_ERROR", "GT not in semantic scores"
    if semantic_scores[gt_shop_id] < 0.35:
        return "SEMANTIC_THRESHOLD_ERROR", f"score={semantic_scores[gt_shop_id]:.3f} < 0.35"
    if gt_shop_id not in ranked_ids[:k]:
        pos = ranked_ids.index(gt_shop_id) + 1 if gt_shop_id in ranked_ids else -1
        return "RANKING_ERROR", f"GT at position {pos}, > K={k}"
    return "CORRECT", f"GT at position {ranked_ids.index(gt_shop_id) + 1}"


# ── Run experiment for a single variant ──
@dataclass
class CaseResult:
    case_id: str
    query: str
    gt_count: int
    gt_ids: List[int]
    ranked_ids: List[int]
    recall_3: float
    ndcg_3: float
    top1: float
    cvr: float
    failure_attribution: Dict[str, int]
    layer_details: Dict[int, str]

@dataclass
class VariantMetrics:
    recall_at_3: float
    recall_at_5: float
    ndcg_at_3: float
    ndcg_at_5: float
    top1_accuracy: float
    constraint_violation_rate: float
    failure_attribution: Dict[str, int]

def run_variant(
    dataset: List[AblationCase],
    shops: Dict[int, Shop],
    profiles: Dict[int, ShopProfile],
    reviews_by_shop: Dict[int, List[ReviewDocument]],
    embeddings: Dict[str, List[float]],
    variant_name: str,
    k: int = 3,
) -> Tuple[List[CaseResult], VariantMetrics]:
    """Run experiment for one variant (A or B)."""
    results: List[CaseResult] = []

    # Aggregate failure attribution
    all_failures: Dict[str, int] = {"CORRECT": 0, "HARD_FILTER_ERROR": 0, "SEMANTIC_RETRIEVAL_ERROR": 0,
                                      "SEMANTIC_THRESHOLD_ERROR": 0, "RANKING_ERROR": 0}

    total_gt = 0
    total_recall_3 = 0.0
    total_recall_5 = 0.0
    total_ndcg_3 = 0.0
    total_ndcg_5 = 0.0
    total_top1 = 0.0
    total_cvr = 0.0

    for case in dataset:
        gt_shops_grade2 = [g for g in case.ground_truth if g.relevance >= 2]
        gt_ids = [g.shop_id for g in gt_shops_grade2]
        gt_with_grades = [(g.shop_id, g.relevance) for g in gt_shops_grade2]

        if not gt_ids:
            continue

        # Hard filter
        hard_matched = compute_hard_matched_ids(case.city, case.expected_constraints, case.latitude, case.longitude, shops, profiles)

        # Query embedding
        query_emb = get_embedding(case.query)

        # Semantic scores via exact cosine
        semantic_scores = compute_semantic_scores_exact(
            query_emb, hard_matched, reviews_by_shop, embeddings, shops
        )

        # Apply threshold 0.35
        filtered_scores = {sid: score for sid, score in semantic_scores.items() if score >= 0.35}

        # Rank all hard-matched shops
        scored = []
        for sid in hard_matched:
            shop = shops[sid]
            profile = profiles.get(sid)
            sem_score = filtered_scores.get(sid)
            # Evidence count: each shop has 6 review docs
            evidence_count = len(reviews_by_shop.get(sid, []))
            score = compute_rank_score(shop, profile, case.expected_constraints, sem_score, case.latitude, case.longitude, evidence_count)
            scored.append((sid, score))

        # Sort by score descending
        scored.sort(key=lambda x: -x[1])
        ranked_ids = [sid for sid, _ in scored]

        # Metrics
        r3 = recall_at_k(gt_ids, ranked_ids, 3)
        r5 = recall_at_k(gt_ids, ranked_ids, 5)
        n3 = ndcg_at_k(gt_with_grades, ranked_ids, 3)
        n5 = ndcg_at_k(gt_with_grades, ranked_ids, 5)
        t1 = top1_accuracy(gt_ids, ranked_ids)
        cvr = constraint_violation_rate(ranked_ids, shops, case.expected_constraints, case.latitude, case.longitude)

        # Failure attribution
        layer_details = {}
        case_failures: Dict[str, int] = {"CORRECT": 0, "HARD_FILTER_ERROR": 0, "SEMANTIC_RETRIEVAL_ERROR": 0,
                                           "SEMANTIC_THRESHOLD_ERROR": 0, "RANKING_ERROR": 0}
        for gt in gt_shops_grade2:
            layer, detail = diagnose_failure_layer(gt.shop_id, case.expected_constraints, hard_matched, filtered_scores, ranked_ids, k)
            layer_details[gt.shop_id] = layer
            case_failures[layer] = case_failures.get(layer, 0) + 1
            all_failures[layer] = all_failures.get(layer, 0) + 1

        results.append(CaseResult(
            case_id=case.case_id,
            query=case.query,
            gt_count=len(gt_ids),
            gt_ids=gt_ids,
            ranked_ids=ranked_ids[:5],
            recall_3=r3,
            ndcg_3=n3,
            top1=t1,
            cvr=cvr,
            failure_attribution=case_failures,
            layer_details=layer_details,
        ))

        total_gt += len(gt_ids)
        total_recall_3 += r3
        total_recall_5 += r5
        total_ndcg_3 += n3
        total_ndcg_5 += n5
        total_top1 += t1
        total_cvr += cvr

    n = len(results)
    metrics = VariantMetrics(
        recall_at_3=round(total_recall_3 / n, 4) if n > 0 else 0.0,
        recall_at_5=round(total_recall_5 / n, 4) if n > 0 else 0.0,
        ndcg_at_3=round(total_ndcg_3 / n, 4) if n > 0 else 0.0,
        ndcg_at_5=round(total_ndcg_5 / n, 4) if n > 0 else 0.0,
        top1_accuracy=round(total_top1 / n, 4) if n > 0 else 0.0,
        constraint_violation_rate=round(total_cvr / n, 4) if n > 0 else 0.0,
        failure_attribution=all_failures,
    )

    return results, metrics


# ── Production Anchor ──
def run_production_anchor(
    dataset: List[AblationCase],
    shops: Dict[int, Shop],
    profiles: Dict[int, ShopProfile],
    reviews_by_shop: Dict[int, List[ReviewDocument]],
    milvus_snapshot: Dict[str, List[dict]],
    k: int = 3,
) -> Dict:
    """Compare B exact cosine vs B production Milvus pre-filter.

    For each query, compare the ranked lists from:
    - B exact cosine (ground truth for "what exact cosine would produce")
    - B production Milvus (from the existing snapshot, what Milvus ANN actually returns)

    Metrics: ranked list overlap@K, Kendall tau-like correlation.
    """
    print("\n=== Production Anchor: B exact cosine vs B production Milvus ===")

    total_queries = 0
    total_overlap_3 = 0.0
    total_overlap_5 = 0.0

    for case in dataset:
        gt_shops_grade2 = [g for g in case.ground_truth if g.relevance >= 2]
        gt_ids = [g.shop_id for g in gt_shops_grade2]
        if not gt_ids:
            continue

        # B exact cosine ranked list
        hard_matched = compute_hard_matched_ids(case.city, case.expected_constraints, case.latitude, case.longitude, shops, profiles)
        if not hard_matched:
            continue

        query_emb = get_embedding(case.query)
        # Load B embeddings from cache
        cache_path = os.path.join(os.path.dirname(__file__), "retrieval_tags_ab_embeddings.json")
        if os.path.exists(cache_path):
            with open(cache_path, "r", encoding="utf-8") as f:
                cache = json.load(f)
            emb_b = cache.get("emb_b", {})
        else:
            print(f"  WARNING: No embedding cache found at {cache_path}")
            continue

        semantic_scores = compute_semantic_scores_exact(query_emb, hard_matched, reviews_by_shop, emb_b, shops)
        filtered_scores = {sid: score for sid, score in semantic_scores.items() if score >= 0.35}

        scored_b = []
        for sid in hard_matched:
            shop = shops[sid]
            profile = profiles.get(sid)
            sem_score = filtered_scores.get(sid)
            evidence_count = len(reviews_by_shop.get(sid, []))
            score = compute_rank_score(shop, profile, case.expected_constraints, sem_score, case.latitude, case.longitude, evidence_count)
            scored_b.append((sid, score))
        scored_b.sort(key=lambda x: -x[1])
        exact_ranked = [sid for sid, _ in scored_b]

        # Production Milvus ranked list (from existing snapshot)
        milvus_docs = milvus_snapshot.get(case.case_id, [])
        if not milvus_docs:
            print(f"  {case.case_id}: No Milvus snapshot data")
            continue

        # Aggregate per-shop MAX score from Milvus snapshot
        milvus_shop_scores: Dict[int, float] = {}
        for doc in milvus_docs:
            sid = doc["shop_id"]
            score = doc["score"]
            if sid not in milvus_shop_scores or score > milvus_shop_scores[sid]:
                milvus_shop_scores[sid] = score

        # Apply threshold 0.35
        milvus_filtered = {sid: score for sid, score in milvus_shop_scores.items() if score >= 0.35}

        scored_milvus = []
        for sid in hard_matched:
            shop = shops[sid]
            profile = profiles.get(sid)
            sem_score = milvus_filtered.get(sid)
            evidence_count = len(reviews_by_shop.get(sid, []))
            score = compute_rank_score(shop, profile, case.expected_constraints, sem_score, case.latitude, case.longitude, evidence_count)
            scored_milvus.append((sid, score))
        scored_milvus.sort(key=lambda x: -x[1])
        milvus_ranked = [sid for sid, _ in scored_milvus]

        # Compare top-K
        exact_set_3 = set(exact_ranked[:3])
        milvus_set_3 = set(milvus_ranked[:3])
        overlap_3 = len(exact_set_3 & milvus_set_3) / 3.0

        exact_set_5 = set(exact_ranked[:5])
        milvus_set_5 = set(milvus_ranked[:5])
        overlap_5 = len(exact_set_5 & milvus_set_5) / 5.0

        total_queries += 1
        total_overlap_3 += overlap_3
        total_overlap_5 += overlap_5

        if overlap_3 < 1.0:
            print(f"  {case.case_id}: overlap@3={overlap_3:.2f}, exact={exact_ranked[:3]}, milvus={milvus_ranked[:3]}")

    avg_overlap_3 = round(total_overlap_3 / total_queries, 4) if total_queries > 0 else 0.0
    avg_overlap_5 = round(total_overlap_5 / total_queries, 4) if total_queries > 0 else 0.0

    print(f"\nProduction Anchor Results ({total_queries} queries):")
    print(f"  Average overlap@3: {avg_overlap_3}")
    print(f"  Average overlap@5: {avg_overlap_5}")

    return {
        "queries_compared": total_queries,
        "avg_overlap_at_3": avg_overlap_3,
        "avg_overlap_at_5": avg_overlap_5,
    }


# ── Report generation ──
def generate_report(
    results_a: List[CaseResult],
    metrics_a: VariantMetrics,
    results_b: List[CaseResult],
    metrics_b: VariantMetrics,
    dataset: List[AblationCase],
    anchor_result: Dict,
    output_path: str,
):
    report = {
        "experiment_date": "2026-08-31",
        "experiment": "Review Embedding Content-only vs Content+Tags A/B",
        "dataset_version": "ablation-v1",
        "dataset_size": len(dataset),
        "method": "exact cosine over allowed set (not ANN)",
        "variants": {
            "A_content_only": {
                "embedding_text": '商户：{name}。评价证据：{content}。标签：',
                "recall_at_3": metrics_a.recall_at_3,
                "recall_at_5": metrics_a.recall_at_5,
                "ndcg_at_3": metrics_a.ndcg_at_3,
                "ndcg_at_5": metrics_a.ndcg_at_5,
                "top1_accuracy": metrics_a.top1_accuracy,
                "constraint_violation_rate": metrics_a.constraint_violation_rate,
                "failure_attribution": metrics_a.failure_attribution,
            },
            "B_content_plus_tags": {
                "embedding_text": '商户：{name}。评价证据：{content}。标签：{tags}',
                "recall_at_3": metrics_b.recall_at_3,
                "recall_at_5": metrics_b.recall_at_5,
                "ndcg_at_3": metrics_b.ndcg_at_3,
                "ndcg_at_5": metrics_b.ndcg_at_5,
                "top1_accuracy": metrics_b.top1_accuracy,
                "constraint_violation_rate": metrics_b.constraint_violation_rate,
                "failure_attribution": metrics_b.failure_attribution,
            },
        },
        "delta_B_minus_A": {
            "recall_at_3_pp": round((metrics_b.recall_at_3 - metrics_a.recall_at_3) * 100, 2),
            "recall_at_5_pp": round((metrics_b.recall_at_5 - metrics_a.recall_at_5) * 100, 2),
            "ndcg_at_3_pp": round((metrics_b.ndcg_at_3 - metrics_a.ndcg_at_3) * 100, 2),
            "ndcg_at_5_pp": round((metrics_b.ndcg_at_5 - metrics_a.ndcg_at_5) * 100, 2),
            "top1_accuracy_pp": round((metrics_b.top1_accuracy - metrics_a.top1_accuracy) * 100, 2),
            "constraint_violation_rate_pp": round((metrics_b.constraint_violation_rate - metrics_a.constraint_violation_rate) * 100, 2),
        },
        "decision": {
            "rule": "Keep B (content+tags) only if Recall@3 >= +3pp AND NDCG@3/Top1/CVR not worse",
            "recall_3_condition_met": (metrics_b.recall_at_3 - metrics_a.recall_at_3) >= 0.03,
            "ndcg_3_not_worse": metrics_b.ndcg_at_3 >= metrics_a.ndcg_at_3 - 0.005,
            "top1_not_worse": metrics_b.top1_accuracy >= metrics_a.top1_accuracy - 0.005,
            "cvr_not_worse": metrics_b.constraint_violation_rate <= metrics_a.constraint_violation_rate + 0.01,
        },
        "production_anchor": anchor_result,
        "per_case_results": [
            {
                "case_id": r_a.case_id,
                "query": r_a.query,
                "gt_count": r_a.gt_count,
                "a_top_k": r_a.ranked_ids[:3],
                "a_recall_3": r_a.recall_3,
                "a_ndcg_3": r_a.ndcg_3,
                "a_top1": r_a.top1,
                "b_top_k": r_b.ranked_ids[:3],
                "b_recall_3": r_b.recall_3,
                "b_ndcg_3": r_b.ndcg_3,
                "b_top1": r_b.top1,
                "a_failure_attribution": r_a.failure_attribution,
                "b_failure_attribution": r_b.failure_attribution,
            }
            for r_a, r_b in zip(results_a, results_b)
        ],
    }

    # Determine final decision
    decision = report["decision"]
    keep_b = decision["recall_3_condition_met"] and decision["ndcg_3_not_worse"] and decision["top1_not_worse"] and decision["cvr_not_worse"]
    report["decision"]["keep_b"] = keep_b
    report["decision"]["recommendation"] = "content+tags (B)" if keep_b else "content-only (A)"

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print(f"\nReport saved: {output_path}")
    return report


def print_summary(report: dict):
    print("\n" + "=" * 70)
    print(" Review Embedding A/B Experiment Results")
    print("=" * 70)
    print(f" Dataset: {report['dataset_version']} ({report['dataset_size']} cases)")
    print(f" Method: {report['method']}")
    print()

    print(f"{'Metric':<25} {'A (content-only)':<20} {'B (content+tags)':<20} {'Δ':<10}")
    print("-" * 75)
    for m in ["recall_at_3", "recall_at_5", "ndcg_at_3", "ndcg_at_5", "top1_accuracy", "constraint_violation_rate"]:
        a = report["variants"]["A_content_only"][m]
        b = report["variants"]["B_content_plus_tags"][m]
        delta_key = m + "_pp"
d = report["delta_B_minus_A"].get(delta_key, 0)
        print(f"{m:<25} {a:<20.4f} {b:<20.4f} {d:>+8.2f}pp")

    print()
    print("Failure Attribution:")
    print(f"{'Layer':<30} {'A':<10} {'B':<10}")
    print("-" * 50)
    for layer in ["CORRECT", "HARD_FILTER_ERROR", "SEMANTIC_RETRIEVAL_ERROR", "SEMANTIC_THRESHOLD_ERROR", "RANKING_ERROR"]:
        a = report["variants"]["A_content_only"]["failure_attribution"].get(layer, 0)
        b = report["variants"]["B_content_plus_tags"]["failure_attribution"].get(layer, 0)
        print(f"{layer:<30} {a:<10} {b:<10}")

    print()
    print("Decision:")
    for key, val in report["decision"].items():
        print(f"  {key}: {val}")

    if report.get("production_anchor"):
        print(f"\nProduction Anchor:")
        for key, val in report["production_anchor"].items():
            print(f"  {key}: {val}")


# ── Main ──
def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    dataset_path = os.path.join(script_dir, "retrieval_ablation_dataset.json")
    shops_path = os.path.join(script_dir, "retrieval_ablation_shops.csv")
    profiles_path = os.path.join(script_dir, "retrieval_ablation_profiles.csv")
    reviews_path = os.path.join(script_dir, "retrieval_ablation_reviews.csv")
    cache_path = os.path.join(script_dir, "retrieval_tags_ab_embeddings.json")
    output_path = os.path.join(script_dir, "retrieval_tags_ab_results.json")
    milvus_snapshot_path = os.path.join(script_dir, "retrieval_ablation_milvus_snapshot.json")

    # Load data
    print("Loading data...")
    dataset = load_dataset(dataset_path)
    shops = load_shops(shops_path)
    profiles = load_profiles(profiles_path)
    reviews_by_shop = load_review_documents(reviews_path)
    print(f"  Dataset: {len(dataset)} cases")
    print(f"  Shops: {len(shops)}")
    print(f"  Profiles: {len(profiles)}")
    print(f"  Review docs: {sum(len(v) for v in reviews_by_shop.values())}")

    # Pre-compute embeddings
    print("\nPre-computing A/B embeddings...")
    emb_a, emb_b = precompute_embeddings(shops, reviews_by_shop, cache_path)
    print(f"  A embeddings: {len(emb_a)}")
    print(f"  B embeddings: {len(emb_b)}")

    # Run variant A (content-only)
    print("\n=== Running variant A (content-only) ===")
    results_a, metrics_a = run_variant(dataset, shops, profiles, reviews_by_shop, emb_a, "A")
    print(f"  Recall@3: {metrics_a.recall_at_3:.4f}")
    print(f"  NDCG@3: {metrics_a.ndcg_at_3:.4f}")
    print(f"  Top1: {metrics_a.top1_accuracy:.4f}")
    print(f"  CVR: {metrics_a.constraint_violation_rate:.4f}")

    # Run variant B (content+tags)
    print("\n=== Running variant B (content+tags) ===")
    results_b, metrics_b = run_variant(dataset, shops, profiles, reviews_by_shop, emb_b, "B")
    print(f"  Recall@3: {metrics_b.recall_at_3:.4f}")
    print(f"  NDCG@3: {metrics_b.ndcg_at_3:.4f}")
    print(f"  Top1: {metrics_b.top1_accuracy:.4f}")
    print(f"  CVR: {metrics_b.constraint_violation_rate:.4f}")

    # Production Anchor
    anchor_result = {"queries_compared": 0, "avg_overlap_at_3": 0.0, "avg_overlap_at_5": 0.0}
    if os.path.exists(milvus_snapshot_path):
        print("\n=== Running Production Anchor ===")
        milvus_snapshot = json.load(open(milvus_snapshot_path, "r", encoding="utf-8"))
        anchor_result = run_production_anchor(dataset, shops, profiles, reviews_by_shop, milvus_snapshot)

    # Generate report
    print("\n=== Generating report ===")
    report = generate_report(results_a, metrics_a, results_b, metrics_b, dataset, anchor_result, output_path)
    print_summary(report)


if __name__ == "__main__":
    main()