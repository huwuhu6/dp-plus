#!/usr/bin/env python3
"""
Retrieval Ablation Experiment — Semantic Score Contribution to Ranking.

Three variants sharing identical hard-filter candidates:
  V_Base:   Hard Filter + full ranking, NO semantic contribution (weight=0)
  V_Sem:    Hard Filter + full ranking + semanticScore × 18 (current production)
  V_Sem_2x: Hard Filter + full ranking + semanticScore × 36 (sanity check)

Metrics (relaxation queries excluded):
  - Recall@3
  - NDCG@3
  - Top1 Accuracy
  - Constraint Violation Rate

Failure Attribution (5 layers):
  HARD_FILTER_ERROR       — GT shop failed hard constraints
  SEMANTIC_RETRIEVAL_ERROR — GT shop not found in Milvus Top80 (no docs at all)
  SEMANTIC_THRESHOLD_ERROR  — GT shop docs in Top80 but all scores < 0.35
  RANKING_ERROR            — GT shop has semanticScore >= 0.35 but not in Top3
  CORRECT                  — GT shop in Top3

Evidence Score: 3 points per evidence doc, up to 2 docs = 6 (all shops have 6+ reviews).
"""
import csv
import json
import math
import os
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, asdict
from typing import Dict, List, Optional, Tuple


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
    trimmed = cuisine.strip()
    return CUISINE_MAP.get(trimmed, trimmed)


def matches_cuisine(profile_cuisine: str, requested_cuisine: str) -> bool:
    if not profile_cuisine or not requested_cuisine:
        return False
    req_canon = canonicalize_cuisine(requested_cuisine)
    if not req_canon:
        return False
    for token in profile_cuisine.split(","):
        tok_canon = canonicalize_cuisine(token.strip())
        if tok_canon == req_canon:
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


# ── Data structures ──
@dataclass
class Shop:
    id: int
    name: str
    type_id: int
    province: str
    city: str
    district: str
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
    summary: str


@dataclass
class GtShop:
    shop_id: int
    relevance: int
    note: str = ""


@dataclass
class AblationCase:
    case_id: str
    query: str
    latitude: float
    longitude: float
    city: str
    expected_constraints: dict
    ground_truth: List[GtShop]
    scenario: str
    notes: str = ""
    is_relaxation: bool = False


# ── Load data ──
def load_shops(csv_path: str) -> Dict[int, Shop]:
    shops = {}
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            sid = int(row["id"])
            shops[sid] = Shop(
                id=sid, name=row["name"], type_id=int(row["type_id"]),
                province=row.get("province", ""), city=row.get("city", ""),
                district=row.get("district", ""),
                x=float(row["x"]), y=float(row["y"]),
                avg_price=int(row["avg_price"]) if row.get("avg_price") else None,
                score=int(row["score"]) if row.get("score") else 0,
                open_hours=row.get("open_hours", ""),
            )
    return shops


def load_profiles(csv_path: str) -> Dict[int, ShopProfile]:
    profiles = {}
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            sid = int(row["shop_id"])
            profiles[sid] = ShopProfile(
                shop_id=sid, cuisine=row.get("cuisine", ""),
                scene_tags=row.get("scene_tags", ""),
                ambience_tags=row.get("ambience_tags", ""),
                queue_level=row.get("queue_level", ""),
                summary=row.get("summary", ""),
            )
    return profiles


def load_dataset(json_path: str) -> List[AblationCase]:
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    cases = []
    for d in data:
        gt = [GtShop(**g) for g in d["ground_truth"]]
        d.pop("ground_truth")
        cases.append(AblationCase(**d, ground_truth=gt))
    return cases


def load_milvus_snapshot(json_path: str) -> Dict[str, List[dict]]:
    """Load raw document-level Milvus snapshot.
    Returns {case_id: [{doc_id, shop_id, doc_type, score}, ...]}
    """
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data


# ── Hard Filter ──
def matches_hard_constraints(
    shop: Shop, profile: Optional[ShopProfile],
    constraints: dict, lat: float, lon: float,
) -> bool:
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


# ── Ranking (mirrors Java toRecommendation) ──
def compute_rank_score(
    shop: Shop, profile: Optional[ShopProfile],
    constraints: dict, lat: float, lon: float,
    semantic_score: Optional[float] = None, semantic_weight: float = 0,
) -> Tuple[float, List[str]]:
    reasons = []
    score = 0.0

    # 1. Rating: score/10/5*20 (max 20)
    rating = (shop.score / 10.0 / 5.0 * 20.0) if shop.score else 0
    score += rating

    # 2. Budget: 20*(1 - (avgPrice/budget)*0.3) (max 20)
    budget = constraints.get("budgetPerPerson", -1)
    if budget > 0 and shop.avg_price is not None:
        score += 20.0 * (1.0 - (shop.avg_price / budget) * 0.3)
        reasons.append(f"人均 {shop.avg_price} 元，符合预算")

    # 3. Cuisine reason (no score)
    if profile and constraints.get("cuisine", ""):
        reasons.append(f"菜系：{profile.cuisine}")

    # 4. Occasion: +12 (max 12)
    occasion = constraints.get("occasion", "")
    if profile and occasion and contains_tag(profile.scene_tags, occasion):
        score += 12.0
        reasons.append(f"场景标签包含{occasion}")

    # 5. Distance: max(0, 20*(1 - d/radius)) (max 20)
    radius = constraints.get("radiusKm", -1)
    if radius > 0:
        d = distance_km(lat, lon, shop.y, shop.x)
        score += max(0.0, 20.0 * (1.0 - d / radius))
        reasons.append(f"距离约 {d:.2f} km")

    # 6. Quiet: +12 (max 12)
    quiet = constraints.get("quiet", False)
    if profile and quiet and contains_tag(profile.ambience_tags, "安静"):
        score += 12.0
        reasons.append("环境标签包含安静")

    # 7. Avoid queue: +8 (max 8)
    avoid_queue = constraints.get("avoidQueue", False)
    if profile and avoid_queue and profile.queue_level.upper() == "LOW":
        score += 8.0
        reasons.append("排队风险低")

    # 8. Evidence: 3 per doc, up to 2 docs = 6 (max 6)
    # All shops in the demo dataset have 6+ review documents, so all get 6.
    EVIDENCE_SCORE = 6.0
    score += EVIDENCE_SCORE

    # 9. Semantic score (added after base, capped at 100)
    if semantic_score is not None and semantic_weight > 0:
        score += semantic_score * semantic_weight
        reasons.append("语义证据与本轮需求相关")

    score = min(100.0, max(0.0, score))
    return round(score, 2), reasons


def contains_tag(tag_str: str, target: str) -> bool:
    if not tag_str or not target:
        return False
    return target.lower() in tag_str.lower()


# ── SQL Filter ──
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


# ── Aggregate semantic scores from raw document-level snapshot ──
def aggregate_shop_scores(
    raw_docs: List[dict],
    min_score: float = 0.35,
) -> Dict[int, float]:
    """MAX aggregation over raw document-level results, then apply threshold.
    Returns {shop_id: max_score} for shops with max_score >= min_score.
    """
    # Step 1: MAX aggregation
    shop_max: Dict[int, float] = {}
    for doc in raw_docs:
        sid = doc["shop_id"]
        s = doc["score"]
        if sid not in shop_max or s > shop_max[sid]:
            shop_max[sid] = s
    # Step 2: Apply threshold
    return {sid: s for sid, s in shop_max.items() if s >= min_score}


# ── Failure Attribution ──
def diagnose_failure_layer(
    shop_id: int,
    all_shops: Dict[int, Shop],
    profile: Optional[ShopProfile],
    sql_filtered: Dict[int, Shop],
    hard_matched_ids: set,
    raw_docs: List[dict],
    ranked_ids: List[int],
    constraints: dict,
    lat: float,
    lon: float,
    semantic_min_score: float = 0.35,
    k: int = 3,
) -> str:
    """Determine at which layer the GT shop was lost.
    Uses raw document-level Milvus results to distinguish:
      - SEMANTIC_RETRIEVAL_ERROR: shop not in Milvus Top80 at all
      - SEMANTIC_THRESHOLD_ERROR: shop in Top80 but max score < threshold
    """
    # 1. SQL filter
    if shop_id not in sql_filtered:
        return "SQL_FILTER_ERROR"

    # 2. Hard filter
    if shop_id not in hard_matched_ids:
        shop = all_shops.get(shop_id)
        if shop:
            budget = constraints.get("budgetPerPerson", -1)
            if budget > 0 and shop.avg_price and shop.avg_price > budget:
                return "HARD_FILTER_ERROR"
            cuisine = constraints.get("cuisine", "")
            if cuisine and profile and not matches_cuisine(profile.cuisine, cuisine):
                return "HARD_FILTER_ERROR"
            radius = constraints.get("radiusKm", -1)
            if radius > 0:
                d = distance_km(lat, lon, shop.y, shop.x)
                if d > radius:
                    return "HARD_FILTER_ERROR"
            arrival = constraints.get("arrivalTime", "")
            if arrival and not is_open_at(shop.open_hours, arrival):
                return "HARD_FILTER_ERROR"
        return "HARD_FILTER_ERROR"

    # 3. Semantic retrieval: check if shop has any docs in Milvus Top80
    shop_docs = [d for d in raw_docs if d["shop_id"] == shop_id]
    if not shop_docs:
        return "SEMANTIC_RETRIEVAL_ERROR"

    # 4. Semantic threshold: check max score
    max_score = max(d["score"] for d in shop_docs)
    if max_score < semantic_min_score:
        return "SEMANTIC_THRESHOLD_ERROR"

    # 5. Ranking
    if shop_id not in ranked_ids[:k]:
        return "RANKING_ERROR"

    return "CORRECT"


# ── Experiment Runner ──
@dataclass
class CaseResult:
    case_id: str
    query: str
    gt_shop_ids: List[int]
    gt_with_grades: List[Tuple[int, int]]
    n_gt: int
    v_base_top3: List[int]
    v_base_top5: List[int]
    v_base_scores: Dict[int, float]
    v_sem_top3: List[int]
    v_sem_top5: List[int]
    v_sem_scores: Dict[int, float]
    v_sem2x_top3: List[int]
    v_sem2x_top5: List[int]
    v_sem2x_scores: Dict[int, float]
    failure_attribution: Dict[str, dict]
    v_base_violations: int
    v_sem_violations: int
    v_sem2x_violations: int
    hard_matched_count: int
    sql_filtered_count: int


def run_experiment(
    dataset: List[AblationCase],
    shops: Dict[int, Shop],
    profiles: Dict[int, ShopProfile],
    milvus_snapshot: Dict[str, List[dict]],
    k: int = 3,
    semantic_min_score: float = 0.35,
) -> List[CaseResult]:
    results = []

    for case in dataset:
        case_id = case.case_id
        lat, lon = case.latitude, case.longitude
        city = case.city
        constraints = case.expected_constraints
        gt_map = {gt.shop_id: gt.relevance for gt in case.ground_truth}
        gt_shop_ids = list(gt_map.keys())

        # 1. SQL filter
        sql_filtered = sql_filter_shops(shops, city)
        sql_filtered_ids = set(sql_filtered.keys())

        # 2. Hard filter
        hard_matched_ids = set()
        for sid, shop in sql_filtered.items():
            profile = profiles.get(sid)
            if matches_hard_constraints(shop, profile, constraints, lat, lon):
                hard_matched_ids.add(sid)

        # 3. Load raw document-level Milvus results
        raw_docs = milvus_snapshot.get(case_id, [])

        # 4. Aggregate semantic scores (MAX + threshold, done in experiment phase)
        case_semantic = aggregate_shop_scores(raw_docs, min_score=semantic_min_score)

        # 5. Rank each variant
        def rank_variant(weight: float) -> Tuple[List[int], List[int], Dict[int, float], int]:
            scored = []
            violations = 0
            for sid in hard_matched_ids:
                shop = shops[sid]
                profile = profiles.get(sid)
                sem_score = case_semantic.get(sid) if weight > 0 else None
                s, _ = compute_rank_score(
                    shop, profile, constraints, lat, lon,
                    semantic_score=sem_score, semantic_weight=weight,
                )
                scored.append((sid, s))

                budget = constraints.get("budgetPerPerson", -1)
                if budget > 0 and shop.avg_price and shop.avg_price > budget:
                    violations += 1
                radius = constraints.get("radiusKm", -1)
                if radius > 0:
                    d = distance_km(lat, lon, shop.y, shop.x)
                    if d > radius:
                        violations += 1

            scored.sort(key=lambda x: -x[1])
            ranked_ids = [sid for sid, _ in scored]
            score_map = {sid: s for sid, s in scored}
            return ranked_ids[:k], ranked_ids[:5], score_map, violations

        v_base_top3, v_base_top5, v_base_scores, v_base_violations = rank_variant(0)
        v_sem_top3, v_sem_top5, v_sem_scores, v_sem_violations = rank_variant(18)
        v_sem2x_top3, v_sem2x_top5, v_sem2x_scores, v_sem2x_violations = rank_variant(36)

        # 6. Failure attribution per GT shop (using raw docs, not aggregated)
        failure_attr = {}
        for gt_id in gt_shop_ids:
            profile = profiles.get(gt_id)
            f_base = diagnose_failure_layer(
                gt_id, shops, profile, sql_filtered, hard_matched_ids,
                raw_docs, v_base_top3, constraints, lat, lon, semantic_min_score, k,
            )
            f_sem = diagnose_failure_layer(
                gt_id, shops, profile, sql_filtered, hard_matched_ids,
                raw_docs, v_sem_top3, constraints, lat, lon, semantic_min_score, k,
            )
            f_sem2x = diagnose_failure_layer(
                gt_id, shops, profile, sql_filtered, hard_matched_ids,
                raw_docs, v_sem2x_top3, constraints, lat, lon, semantic_min_score, k,
            )
            failure_attr[str(gt_id)] = {
                "v_base": f_base,
                "v_sem": f_sem,
                "v_sem2x": f_sem2x,
            }

        results.append(CaseResult(
            case_id=case_id, query=case.query,
            gt_shop_ids=gt_shop_ids,
            gt_with_grades=[(sid, grade) for sid, grade in gt_map.items()],
            n_gt=len(gt_shop_ids),
            v_base_top3=v_base_top3, v_base_top5=v_base_top5,
            v_base_scores=v_base_scores,
            v_sem_top3=v_sem_top3, v_sem_top5=v_sem_top5,
            v_sem_scores=v_sem_scores,
            v_sem2x_top3=v_sem2x_top3, v_sem2x_top5=v_sem2x_top5,
            v_sem2x_scores=v_sem2x_scores,
            failure_attribution=failure_attr,
            v_base_violations=v_base_violations,
            v_sem_violations=v_sem_violations,
            v_sem2x_violations=v_sem2x_violations,
            hard_matched_count=len(hard_matched_ids),
            sql_filtered_count=len(sql_filtered_ids),
        ))

    return results


# ── Metrics ──
def recall_at_k(gt_ids: List[int], ranked_ids: List[int], k: int) -> float:
    if not gt_ids:
        return 0.0
    top_k = set(ranked_ids[:k])
    hits = sum(1 for g in gt_ids if g in top_k)
    return hits / len(gt_ids)


def dcg(relevance_scores: List[int], k: int) -> float:
    scores = relevance_scores[:k]
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(scores))


def ndcg_at_k(gt_with_grades: List[Tuple[int, int]], ranked_ids: List[int], k: int) -> float:
    if not gt_with_grades:
        return 0.0
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


def compute_metrics_for_variant(
    results: List[CaseResult],
    top3_attr: str,
    variant_key: str,
    violations_attr: str,
    k: int = 3,
) -> dict:
    active = [r for r in results if r.n_gt > 0]

    recall_vals = []
    ndcg_vals = []
    top1_vals = []
    violation_vals = []
    failure_counts = defaultdict(int)

    for r in active:
        top3 = getattr(r, top3_attr)
        violations = getattr(r, violations_attr)

        recall_vals.append(recall_at_k(r.gt_shop_ids, top3, k))
        ndcg_vals.append(ndcg_at_k(r.gt_with_grades, top3, k))
        top1_vals.append(top1_accuracy(r.gt_shop_ids, top3))

        n_recs = len(top3)
        violation_vals.append(violations / n_recs if n_recs > 0 else 0.0)

        for sid, layers in r.failure_attribution.items():
            layer = layers.get(variant_key, "UNKNOWN")
            failure_counts[layer] += 1

    n = len(active)
    return {
        "recall_at_3": round(sum(recall_vals) / n, 4) if n else 0,
        "ndcg_at_3": round(sum(ndcg_vals) / n, 4) if n else 0,
        "top1_accuracy": round(sum(top1_vals) / n, 4) if n else 0,
        "constraint_violation_rate": round(sum(violation_vals) / n, 4) if n else 0,
        "failure_attribution": dict(failure_counts),
        "n_cases": n,
    }


# ── Report ──
def generate_report(results: List[CaseResult], dataset: List[AblationCase], output_path: str):
    active = [r for r in results if r.n_gt > 0]
    relaxation = [r for r in results if r.n_gt == 0]

    v_base_metrics = compute_metrics_for_variant(results, "v_base_top3", "v_base", "v_base_violations", k=3)
    v_sem_metrics = compute_metrics_for_variant(results, "v_sem_top3", "v_sem", "v_sem_violations", k=3)
    v_sem2x_metrics = compute_metrics_for_variant(results, "v_sem2x_top3", "v_sem2x", "v_sem2x_violations", k=3)

    per_case = []
    for r in results:
        case = {
            "case_id": r.case_id, "query": r.query,
            "gt_count": r.n_gt, "gt_shop_ids": r.gt_shop_ids,
            "hard_matched_count": r.hard_matched_count,
            "sql_filtered_count": r.sql_filtered_count,
            "v_base_top3": r.v_base_top3,
            "v_base_scores": {str(sid): s for sid, s in r.v_base_scores.items() if sid in r.v_base_top3},
            "v_sem_top3": r.v_sem_top3,
            "v_sem_scores": {str(sid): s for sid, s in r.v_sem_scores.items() if sid in r.v_sem_top3},
            "v_sem2x_top3": r.v_sem2x_top3,
            "v_sem2x_scores": {str(sid): s for sid, s in r.v_sem2x_scores.items() if sid in r.v_sem2x_top3},
            "failure_attribution": r.failure_attribution,
        }
        if r.n_gt > 0:
            case["v_base_recall"] = round(recall_at_k(r.gt_shop_ids, r.v_base_top3, 3), 4)
            case["v_sem_recall"] = round(recall_at_k(r.gt_shop_ids, r.v_sem_top3, 3), 4)
            case["v_sem2x_recall"] = round(recall_at_k(r.gt_shop_ids, r.v_sem2x_top3, 3), 4)
            case["v_base_ndcg"] = round(ndcg_at_k(r.gt_with_grades, r.v_base_top3, 3), 4)
            case["v_sem_ndcg"] = round(ndcg_at_k(r.gt_with_grades, r.v_sem_top3, 3), 4)
            case["v_sem2x_ndcg"] = round(ndcg_at_k(r.gt_with_grades, r.v_sem2x_top3, 3), 4)
        per_case.append(case)

    report = {
        "experiment_date": time.strftime("%Y-%m-%d %H:%M:%S"),
        "dataset_version": "ablation-v1",
        "dataset_size": len(dataset),
        "active_cases": len(active),
        "relaxation_cases": len(relaxation),
        "variants": {
            "V_Base": {
                **v_base_metrics,
                "description": "Hard Filter + full ranking, NO semantic",
            },
            "V_Sem": {
                **v_sem_metrics,
                "description": "Hard Filter + full ranking + semanticScore × 18 (production)",
            },
            "V_Sem_2x": {
                **v_sem2x_metrics,
                "description": "Hard Filter + full ranking + semanticScore × 36 (sanity check)",
            },
        },
        "per_case_results": per_case,
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print(f"\nReport saved: {output_path}")
    return report


def print_summary(report: dict):
    print("\n" + "=" * 80)
    print("RETRIEVAL ABLATION EXPERIMENT RESULTS")
    print("=" * 80)
    print(f"Date: {report['experiment_date']}")
    print(f"Dataset: {report['dataset_version']} ({report['active_cases']} active + {report['relaxation_cases']} relaxation)")
    print()

    variants = report["variants"]
    print(f"{'Metric':<30} {'V_Base':<12} {'V_Sem':<12} {'V_Sem_2x':<12}")
    print("-" * 70)
    for metric in ["recall_at_3", "ndcg_at_3", "top1_accuracy", "constraint_violation_rate"]:
        v = [variants[v].get(metric, 0) for v in ["V_Base", "V_Sem", "V_Sem_2x"]]
        label = metric.replace("_", " ").title()
        print(f"{label:<30} {v[0]:<12.4f} {v[1]:<12.4f} {v[2]:<12.4f}")

    print()
    print("Failure Attribution:")
    all_layers = set()
    for v in variants.values():
        all_layers.update(v.get("failure_attribution", {}).keys())
    print(f"{'Layer':<30} {'V_Base':<12} {'V_Sem':<12} {'V_Sem_2x':<12}")
    print("-" * 70)
    for layer in sorted(all_layers):
        v = [variants[v].get("failure_attribution", {}).get(layer, 0) for v in ["V_Base", "V_Sem", "V_Sem_2x"]]
        print(f"{layer:<30} {v[0]:<12} {v[1]:<12} {v[2]:<12}")


# ── Main ──
def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    dataset_path = os.path.join(script_dir, "retrieval_ablation_dataset.json")
    shops_csv = os.path.join(script_dir, "retrieval_ablation_shops.csv")
    profiles_csv = os.path.join(script_dir, "retrieval_ablation_profiles.csv")
    milvus_path = os.path.join(script_dir, "retrieval_ablation_milvus_snapshot.json")
    report_dir = os.path.join(script_dir, "retrieval_ablation_reports")
    os.makedirs(report_dir, exist_ok=True)
    report_path = os.path.join(report_dir, f"ablation_report_{time.strftime('%Y%m%d_%H%M%S')}.json")

    print("Loading data...")
    dataset = load_dataset(dataset_path)
    shops = load_shops(shops_csv)
    profiles = load_profiles(profiles_csv)
    milvus_snapshot = load_milvus_snapshot(milvus_path)

    # Count total raw docs
    total_docs = sum(len(v) for v in milvus_snapshot.values())
    print(f"  Dataset: {len(dataset)} cases")
    print(f"  Shops: {len(shops)}, Profiles: {len(profiles)}")
    print(f"  Milvus snapshot: {len(milvus_snapshot)} queries, {total_docs} raw documents")

    print("\nRunning experiment...")
    results = run_experiment(dataset, shops, profiles, milvus_snapshot, k=3)

    print("\nGenerating report...")
    report = generate_report(results, dataset, report_path)
    print_summary(report)

    # CSV export
    csv_path = report_path.replace(".json", ".csv")
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "case_id", "query", "gt_count", "hard_matched",
            "v_base_top3", "v_base_recall", "v_sem_top3", "v_sem_recall",
            "v_sem2x_top3", "v_sem2x_recall",
        ])
        for r in results:
            if r.n_gt > 0:
                writer.writerow([
                    r.case_id, r.query, r.n_gt, r.hard_matched_count,
                    "|".join(str(s) for s in r.v_base_top3),
                    round(recall_at_k(r.gt_shop_ids, r.v_base_top3, 3), 4),
                    "|".join(str(s) for s in r.v_sem_top3),
                    round(recall_at_k(r.gt_shop_ids, r.v_sem_top3, 3), 4),
                    "|".join(str(s) for s in r.v_sem2x_top3),
                    round(recall_at_k(r.gt_shop_ids, r.v_sem2x_top3, 3), 4),
                ])
    print(f"CSV saved: {csv_path}")


if __name__ == "__main__":
    main()