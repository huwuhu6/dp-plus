#!/usr/bin/env python3
"""
Milvus Document-Level Semantic Score Snapshot.

For each query in the dataset:
  1. Get embedding vector via DashScope API (text-embedding-v4)
  2. Search Milvus (hmdp_shop_evidence, topK=80, COSINE)
  3. Aggregate document-level scores to shop-level (MAX aggregation)
  4. Save results

Output: retrieval_ablation_milvus_snapshot.json
  {
    "ABL_001": { "shop_id": score, ... },
    ...
  }
"""
import json
import os
import requests
import sys
from typing import Dict, List


DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
MILVUS_HOST = "http://127.0.0.1:19530"
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
FLOAT_EPS = 1e-9


def get_embedding(text: str) -> List[float]:
    """Get embedding vector via DashScope text-embedding-v4."""
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


def search_milvus(vector: List[float], top_k: int = 80) -> List[Dict]:
    """Search Milvus for nearest neighbors."""
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
    return result["data"]


def aggregate_shop_scores(docs: List[Dict]) -> Dict[int, float]:
    """MAX aggregation: for each shop, take max document score (distance)."""
    shop_scores: Dict[int, float] = {}
    for doc in docs:
        meta = json.loads(doc["metadata"])
        shop_id = int(meta["shopId"])
        distance = doc["distance"]
        if shop_id not in shop_scores or distance > shop_scores[shop_id]:
            shop_scores[shop_id] = distance
    return shop_scores


def build_snapshot(dataset_path: str, output_path: str):
    """Build Milvus snapshot for all queries in the dataset."""
    with open(dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    queries = []
    for case in dataset:
        queries.append((case["case_id"], case["query"]))

    snapshot = {}
    n = len(queries)

    for i, (case_id, query) in enumerate(queries):
        print(f"[{i+1}/{n}] {case_id}: {query[:40]}...", end=" ", flush=True)
        try:
            emb = get_embedding(query)
            docs = search_milvus(emb, top_k=80)
            shop_scores = aggregate_shop_scores(docs)
            # Filter by min_score=0.35 (production config)
            filtered = {sid: score for sid, score in shop_scores.items() if score >= 0.35}
            snapshot[case_id] = filtered
            print(f"→ {len(filtered)} shops (of {len(shop_scores)} raw)")
        except Exception as e:
            print(f"ERROR: {e}")
            snapshot[case_id] = {}

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(snapshot, f, ensure_ascii=False, indent=2)

    print(f"\nSnapshot saved: {output_path}")
    print(f"Total queries: {n}")


def print_snapshot_stats(snapshot_path: str):
    """Print statistics about the snapshot."""
    with open(snapshot_path, "r", encoding="utf-8") as f:
        snapshot = json.load(f)

    case_ids = sorted(snapshot.keys())
    for case_id in case_ids:
        scores = snapshot[case_id]
        shop_count = len(scores)
        if shop_count > 0:
            top_shops = sorted(scores.items(), key=lambda x: -x[1])[:5]
            top_str = ", ".join(f"{sid}:{s:.3f}" for sid, s in top_shops)
            print(f"  {case_id}: {shop_count} shops, top5: {top_str}")
        else:
            print(f"  {case_id}: {shop_count} shops (empty)")


if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    dataset_path = os.path.join(script_dir, "retrieval_ablation_dataset.json")
    snapshot_path = os.path.join(script_dir, "retrieval_ablation_milvus_snapshot.json")

    if len(sys.argv) > 1 and sys.argv[1] == "stats":
        print_snapshot_stats(snapshot_path)
    else:
        build_snapshot(dataset_path, snapshot_path)