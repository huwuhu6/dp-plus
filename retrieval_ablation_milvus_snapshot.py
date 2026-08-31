#!/usr/bin/env python3
"""
Milvus Document-Level Semantic Score Snapshot (v2).

Production chain:
  SQL → Hard Filter → allowedShopIds → Milvus (with filter) → raw document-level results

This snapshot preserves RAW document-level results from Milvus.
  - No MAX aggregation in snapshot phase
  - No threshold filtering in snapshot phase
  - Milvus search uses allowedShopIds (from hard filter) to match production

Output: retrieval_ablation_milvus_snapshot.json
  {
    "ABL_001": [
      {"doc_id": "shop-review-xx", "shop_id": 26, "doc_type": "REVIEW", "score": 0.6713},
      ...
    ]
  }
"""
import csv
import json
import math
import os
import requests
import sys
from typing import Dict, List, Optional, Set, Tuple


DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
MILVUS_HOST = "http://127.0.0.1:19530"
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")


# ── Cuisine Canonicalizer (mirrors Java CuisineCanonicalizer) ──
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


# ── Haversine distance ──
def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


# ── Open hours check ──
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


# ── Load data ──
def load_shops(csv_path: str) -> Dict[int, dict]:
    shops = {}
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            sid = int(row["id"])
            shops[sid] = {
                "id": sid,
                "name": row["name"],
                "province": row.get("province", ""),
                "city": row.get("city", ""),
                "x": float(row["x"]),
                "y": float(row["y"]),
                "avg_price": int(row["avg_price"]) if row.get("avg_price") else None,
                "score": int(row["score"]) if row.get("score") else 0,
                "open_hours": row.get("open_hours", ""),
            }
    return shops


def load_profiles(csv_path: str) -> Dict[int, dict]:
    profiles = {}
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            sid = int(row["shop_id"])
            profiles[sid] = {
                "shop_id": sid,
                "cuisine": row.get("cuisine", ""),
                "scene_tags": row.get("scene_tags", ""),
                "ambience_tags": row.get("ambience_tags", ""),
                "queue_level": row.get("queue_level", ""),
            }
    return profiles


# ── Hard Filter (same logic as production matchesHardConstraints) ──
def matches_hard_constraints(
    shop: dict,
    profile: Optional[dict],
    constraints: dict,
    lat: float,
    lon: float,
) -> bool:
    budget = constraints.get("budgetPerPerson", -1)
    if budget > 0 and (shop["avg_price"] is None or shop["avg_price"] > budget):
        return False
    cuisine = constraints.get("cuisine", "")
    if cuisine and (profile is None or not matches_cuisine(profile["cuisine"], cuisine)):
        return False
    radius = constraints.get("radiusKm", -1)
    if radius > 0:
        d = distance_km(lat, lon, shop["y"], shop["x"])
        if d > radius:
            return False
    arrival = constraints.get("arrivalTime", "")
    if arrival and not is_open_at(shop["open_hours"], arrival):
        return False
    return True


def sql_filter_shops(shops: Dict[int, dict], city: str) -> Dict[int, dict]:
    if not city:
        return dict(shops)
    city_norm = city.replace("市", "").strip()
    result = {}
    for sid, shop in shops.items():
        shop_city = shop["city"].replace("市", "").strip() if shop["city"] else ""
        if shop_city == city_norm:
            result[sid] = shop
    return result


def compute_hard_matched_ids(
    city: str,
    constraints: dict,
    lat: float,
    lon: float,
    shops: Dict[int, dict],
    profiles: Dict[int, dict],
) -> Set[int]:
    """Compute the hard-matched shop set (same as production hard filter)."""
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


# ── Milvus search (full collection) ──
def search_milvus_full(vector: List[float], top_k: int = 80) -> List[dict]:
    """Search Milvus entire collection (no filter). Returns raw results."""
    url = f"{MILVUS_HOST}/v2/vectordb/entities/search"
    payload = {
        "collectionName": "hmdp_shop_evidence",
        "annsField": "embedding",
        "data": [vector],
        "limit": top_k,
        "outputFields": ["doc_id", "metadata"],
    }
    resp = requests.post(url, headers={"Content-Type": "application/json"}, json=payload, timeout=60)
    if resp.status_code != 200:
        raise RuntimeError(f"Milvus search error: {resp.status_code} {resp.text}")
    result = resp.json()
    if result.get("code", 0) != 0:
        raise RuntimeError(f"Milvus search error: {result}")
    return result.get("data", [])


# ── Snapshot builder ──
def build_snapshot(dataset_path: str, shops_path: str, profiles_path: str, output_path: str):
    """Build document-level Milvus snapshot with allowedShopIds filtering."""
    with open(dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)
    shops = load_shops(shops_path)
    profiles = load_profiles(profiles_path)

    n = len(dataset)
    snapshot = {}

    for i, case in enumerate(dataset):
        case_id = case["case_id"]
        query = case["query"]
        lat, lon = case["latitude"], case["longitude"]
        city = case["city"]
        constraints = case["expected_constraints"]

        print(f"[{i+1}/{n}] {case_id}: {query[:40]}...", end=" ", flush=True)

        try:
            # Step 1: Compute hard filter candidates (same as production)
            allowed_ids = compute_hard_matched_ids(city, constraints, lat, lon, shops, profiles)
            print(f"allowed={len(allowed_ids)}", end=" ", flush=True)

            if not allowed_ids:
                print("→ 0 docs (no hard-matched shops)")
                snapshot[case_id] = []
                continue

            # Step 2: Get embedding
            emb = get_embedding(query)

            # Step 3: Search entire Milvus collection (no filter — REST API filter is post-filter)
            # Production uses pre-filter (expr), REST API only supports post-filter (filter).
            # To match production intent, search full collection then Python-filter by allowedShopIds.
            all_docs = search_milvus_full(emb, top_k=80)
            print(f"milvus_raw={len(all_docs)}", end=" ", flush=True)

            # Step 4: Filter by allowedShopIds in Python (mimics production pre-filter)
            filtered_docs = [d for d in all_docs if int(json.loads(d["metadata"])["shopId"]) in allowed_ids]
            print(f"after_filter={len(filtered_docs)}", end=" ", flush=True)

            # Step 5: Save RAW document-level results (NO MAX, NO threshold)
            raw_results = []
            for doc in filtered_docs:
                meta = json.loads(doc["metadata"])
                raw_results.append({
                    "doc_id": doc["doc_id"],
                    "shop_id": int(meta["shopId"]),
                    "doc_type": meta.get("documentType", "UNKNOWN"),
                    "score": round(doc["distance"], 6),
                })
            snapshot[case_id] = raw_results
            print(f"→ {len(raw_results)} docs saved")

        except Exception as e:
            print(f"ERROR: {e}")
            snapshot[case_id] = []

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(snapshot, f, ensure_ascii=False, indent=2)

    # Stats
    total_docs = sum(len(v) for v in snapshot.values())
    print(f"\nSnapshot saved: {output_path}")
    print(f"Total queries: {n}, total documents: {total_docs}")


def print_snapshot_stats(snapshot_path: str):
    with open(snapshot_path, "r", encoding="utf-8") as f:
        snapshot = json.load(f)
    for case_id in sorted(snapshot.keys()):
        docs = snapshot[case_id]
        if not docs:
            print(f"  {case_id}: 0 docs")
            continue
        # Group by shop
        shop_docs = {}
        for d in docs:
            sid = d["shop_id"]
            if sid not in shop_docs:
                shop_docs[sid] = []
            shop_docs[sid].append(d)
        # Show per-shop summary
        summary = []
        for sid, sdocs in sorted(shop_docs.items()):
            max_score = max(d["score"] for d in sdocs)
            summary.append(f"{sid}:{max_score:.3f}({len(sdocs)}docs)")
        print(f"  {case_id}: {len(docs)} docs, {len(shop_docs)} shops, {', '.join(summary[:5])}")


if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    dataset_path = os.path.join(script_dir, "retrieval_ablation_dataset.json")
    shops_path = os.path.join(script_dir, "retrieval_ablation_shops.csv")
    profiles_path = os.path.join(script_dir, "retrieval_ablation_profiles.csv")
    snapshot_path = os.path.join(script_dir, "retrieval_ablation_milvus_snapshot.json")

    if len(sys.argv) > 1 and sys.argv[1] == "stats":
        print_snapshot_stats(snapshot_path)
    else:
        build_snapshot(dataset_path, shops_path, profiles_path, snapshot_path)