#!/usr/bin/env python3
"""A/B experiment: deepseek-v4-flash vs qwen-flash for narrative generation and answer polish.

Usage:
    py -3.12 -X utf8 narrative_ab_experiment.py

Output: detailed comparison in console + JSON results file.
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error

API_KEY = os.environ.get('AI_API_KEY') or os.environ.get('DASHSCOPE_API_KEY') or ''
BASE_URL = os.environ.get('AI_BASE_URL') or 'https://dashscope.aliyuncs.com/compatible-mode/v1'
MODEL_A = os.environ.get('AI_MODEL') or 'deepseek-v4-flash'
MODEL_B = 'qwen-flash'

if not API_KEY:
    print("ERROR: No API key found")
    sys.exit(1)

# ---------- Narrative generation samples ----------
# Input: query + matchedReasons (JSON array of strings from first candidate)
# Prompt: system + user
# Output: 1-2 sentence narrative (must pass isSafeNarrative guard)

NARRATIVE_SAMPLES = [
    {
        "id": "N1",
        "scenario": "正常推荐，多理由",
        "query": "重庆适合约会的日料店",
        "matchedReasons": [
            "菜系：日料",
            "场景匹配：约会",
            "距离：1.2km",
            "基础评分：45",
            "安静环境适合聊天"
        ]
    },
    {
        "id": "N2",
        "scenario": "多候选取舍，预算优先",
        "query": "福州性价比高的安静餐厅",
        "matchedReasons": [
            "预算符合度：85%",
            "安静环境适合聊天",
            "菜系：闽菜",
            "低排队风险"
        ]
    },
    {
        "id": "N3",
        "scenario": "特征极少，仅菜系",
        "query": "日料",
        "matchedReasons": [
            "菜系：日料"
        ]
    },
    {
        "id": "N4",
        "scenario": "仅距离+评分",
        "query": "附近有什么吃的",
        "matchedReasons": [
            "距离：0.5km",
            "基础评分：42"
        ]
    },
    {
        "id": "N5",
        "scenario": "自动松弛后推荐",
        "query": "重庆日料",
        "matchedReasons": [
            "菜系：日料",
            "基础评分：48",
            "距离：3.5km"
        ],
        "relaxationNote": "默认附近范围内未找到结果，已在不改变地点、菜系、预算和到店时间等硬条件的前提下扩大搜索范围。"
    },
    {
        "id": "N6",
        "scenario": "长匹配理由，多条件",
        "query": "预算150以内安静的约会餐厅",
        "matchedReasons": [
            "预算符合度：90%",
            "菜系：西餐",
            "场景匹配：约会",
            "安静环境适合聊天",
            "低排队风险",
            "证据数量：5条",
            "距离：1.8km"
        ]
    },
    {
        "id": "N7",
        "scenario": "仅有 1 家候选，SPARSE 模式",
        "query": "广州日料",
        "matchedReasons": [
            "菜系：日料",
            "基础评分：40"
        ],
        "sparseNote": "当前严格条件下仅找到 1 家商户，未自动放宽任何用户条件。"
    },
    {
        "id": "N8",
        "scenario": "非餐饮场景，场景匹配",
        "query": "找个安静的地方看书",
        "matchedReasons": [
            "安静环境适合聊天",
            "距离：0.8km",
            "基础评分：38"
        ]
    },
]

# ---------- Answer polish samples ----------
# Input: userMessage + factualAnswer (raw concatenated tool display text)
# Prompt: system + user
# Output: natural language answer (should preserve all facts, not add new ones)

POLISH_SAMPLES = [
    {
        "id": "P1",
        "scenario": "单店详情查询，简短事实",
        "userMessage": "山崎日本料理怎么样",
        "factualAnswer": "山崎日本料理：人均 280 元，评分 4.5，营业时间 11:00-22:00，地址 重庆市渝中区解放碑步行街。"
    },
    {
        "id": "P2",
        "scenario": "商户对比，结构化事实",
        "userMessage": "山崎日本料理和井上日料哪个更合适",
        "factualAnswer": "商户对比：\n- 山崎日本料理：人均 280 元，评分 4.5，营业时间 11:00-22:00\n- 井上日料：人均 180 元，评分 4.2，营业时间 10:00-21:30\n以上为数据库事实；更适合与否需要结合你的预算、距离和场景偏好。"
    },
    {
        "id": "P3",
        "scenario": "证据不足，无评价",
        "userMessage": "这家店有包间吗",
        "factualAnswer": "当前没有可引用的评价或探店证据。"
    },
    {
        "id": "P4",
        "scenario": "长事实输入，多工具结果拼接",
        "userMessage": "帮我查一下这几家店的详细信息",
        "factualAnswer": "山崎日本料理：人均 280 元，评分 4.5，营业时间 11:00-22:00，地址 重庆市渝中区解放碑步行街。\n\n井上日料：人均 180 元，评分 4.2，营业时间 10:00-21:30，地址 重庆市江北区观音桥步行街。\n\n可引用的评价证据：\n- 食材新鲜，三文鱼入口即化\n- 环境安静，适合约会\n- 价格适中，性价比高\n\n备选商户：\n- 山崎日本料理：人均 280，评分 4.5，距离 1.2km\n- 井上日料：人均 180，评分 4.2，距离 0.8km"
    },
    {
        "id": "P5",
        "scenario": "含具体时间数字",
        "userMessage": "山崎日本料理营业到几点",
        "factualAnswer": "山崎日本料理：人均 280 元，评分 4.5，营业时间 11:00-22:00，地址 重庆市渝中区解放碑步行街。"
    },
    {
        "id": "P6",
        "scenario": "预算限制查询",
        "userMessage": "人均150以内有什么推荐",
        "factualAnswer": "井上日料：人均 180 元，评分 4.2，营业时间 10:00-21:30，地址 重庆市江北区观音桥步行街。\n\n当前没有可引用的评价或探店证据。"
    },
    {
        "id": "P7",
        "scenario": "优惠券查询",
        "userMessage": "这家店有什么优惠券",
        "factualAnswer": "山崎日本料理：工作日午市套餐 8 折优惠，有效期至 2026-09-30；满 200 减 30 优惠券，有效期至 2026-09-15；秒杀库存 50 份。"
    },
    {
        "id": "P8",
        "scenario": "多轮上下文，备选+证据",
        "userMessage": "刚才那家日料店的评价怎么样",
        "factualAnswer": "井上日料：人均 180 元，评分 4.2，营业时间 10:00-21:30，地址 重庆市江北区观音桥步行街。\n\n可引用的评价证据：\n- 性价比很高，经常来\n- 环境一般，但味道不错\n- 高峰期需要排队"
    },
]


def call_model(messages, model, temperature=0.1, timeout=15):
    """Call chat completions API."""
    body = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
    }
    payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
    url = f"{BASE_URL}/chat/completions"
    req = urllib.request.Request(url, data=payload, method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', f'Bearer {API_KEY}')

    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            elapsed = time.time() - start
            result = json.loads(resp.read().decode('utf-8'))
            content = result['choices'][0]['message'].get('content', '')
            usage = result.get('usage', {})
            return {
                'content': content,
                'latency_ms': round(elapsed * 1000),
                'prompt_tokens': usage.get('prompt_tokens', 0),
                'completion_tokens': usage.get('completion_tokens', 0),
                'error': None
            }
    except Exception as e:
        elapsed = time.time() - start
        return {
            'content': '',
            'latency_ms': round(elapsed * 1000),
            'prompt_tokens': 0,
            'completion_tokens': 0,
            'error': str(e)
        }


def run_narrative_test(sample):
    """Run narrative generation test for both models."""
    system_prompt = "你是消费决策助手。仅输出一到两句泛化的取舍建议，不得出现店名、数字、价格、距离、地址、营业时间、评分、证据原文或未提供的事实。"
    user_content = "用户需求：" + sample['query'] + "\n已满足的偏好：" + json.dumps(sample['matchedReasons'], ensure_ascii=False)
    if sample.get('relaxationNote'):
        user_content = "注意：" + sample['relaxationNote'] + "\n" + user_content
    if sample.get('sparseNote'):
        user_content = "注意：" + sample['sparseNote'] + "\n" + user_content

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_content}
    ]

    result_a = call_model(messages, MODEL_A)
    result_b = call_model(messages, MODEL_B)

    # Check isSafeNarrative
    def safe_check(text):
        if not text or len(text) > 240:
            return False, "length>240 or empty"
        import re
        if re.search(r'\d', text):
            return False, "contains digits"
        prohibited = ["人均", "价格", "距离", "公里", "地址", "营业", "评分", "证据", "元"]
        for t in prohibited:
            if t in text:
                return False, f"contains '{t}'"
        return True, "pass"

    safe_a, reason_a = safe_check(result_a['content'])
    safe_b, reason_b = safe_check(result_b['content'])

    return {
        'id': sample['id'],
        'scenario': sample['scenario'],
        'query': sample['query'],
        'model_a': {
            'model': MODEL_A,
            'content': result_a['content'],
            'latency_ms': result_a['latency_ms'],
            'prompt_tokens': result_a['prompt_tokens'],
            'completion_tokens': result_a['completion_tokens'],
            'error': result_a['error'],
            'safe_check': {'passed': safe_a, 'reason': reason_a}
        },
        'model_b': {
            'model': MODEL_B,
            'content': result_b['content'],
            'latency_ms': result_b['latency_ms'],
            'prompt_tokens': result_b['prompt_tokens'],
            'completion_tokens': result_b['completion_tokens'],
            'error': result_b['error'],
            'safe_check': {'passed': safe_b, 'reason': reason_b}
        }
    }


def run_polish_test(sample):
    """Run answer polish test for both models."""
    system_prompt = "你是点评消费决策助手。基于已检索到的事实，用简洁自然的中文回答用户。不得补充、猜测或改写任何事实；证据不足时直接说明。"
    user_content = "用户问题：" + sample['userMessage'] + "\n已检索事实：\n" + sample['factualAnswer']

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_content}
    ]

    result_a = call_model(messages, MODEL_A)
    result_b = call_model(messages, MODEL_B)

    return {
        'id': sample['id'],
        'scenario': sample['scenario'],
        'userMessage': sample['userMessage'],
        'model_a': {
            'model': MODEL_A,
            'content': result_a['content'],
            'latency_ms': result_a['latency_ms'],
            'prompt_tokens': result_a['prompt_tokens'],
            'completion_tokens': result_a['completion_tokens'],
            'error': result_a['error'],
        },
        'model_b': {
            'model': MODEL_B,
            'content': result_b['content'],
            'latency_ms': result_b['latency_ms'],
            'prompt_tokens': result_b['prompt_tokens'],
            'completion_tokens': result_b['completion_tokens'],
            'error': result_b['error'],
        }
    }


# ========== Main ==========
print("=" * 80)
print(f"Model A: {MODEL_A}")
print(f"Model B: {MODEL_B}")
print(f"Base URL: {BASE_URL}")
print("=" * 80)

all_results = {
    'narrative': [],
    'polish': [],
    'summary': {}
}

# === Narrative generation ===
print("\n" + "=" * 80)
print("叙事生成 (NARRATIVE_GENERATION)")
print("=" * 80)
for s in NARRATIVE_SAMPLES:
    print(f"\n--- [{s['id']}] {s['scenario']} ---")
    print(f"  Query: {s['query']}")
    r = run_narrative_test(s)
    all_results['narrative'].append(r)
    print(f"  [{MODEL_A}] latency={r['model_a']['latency_ms']}ms tokens={r['model_a']['prompt_tokens']}+{r['model_a']['completion_tokens']} safe={r['model_a']['safe_check']['passed']}")
    if r['model_a']['content']:
        print(f"    -> {r['model_a']['content'][:120]}")
    if r['model_a']['error']:
        print(f"    ERROR: {r['model_a']['error']}")
    print(f"  [{MODEL_B}] latency={r['model_b']['latency_ms']}ms tokens={r['model_b']['prompt_tokens']}+{r['model_b']['completion_tokens']} safe={r['model_b']['safe_check']['passed']}")
    if r['model_b']['content']:
        print(f"    -> {r['model_b']['content'][:120]}")
    if r['model_b']['error']:
        print(f"    ERROR: {r['model_b']['error']}")

# === Answer polish ===
print("\n" + "=" * 80)
print("答案润色 (AGENT_ANSWER_POLISH)")
print("=" * 80)
for s in POLISH_SAMPLES:
    print(f"\n--- [{s['id']}] {s['scenario']} ---")
    print(f"  User: {s['userMessage']}")
    r = run_polish_test(s)
    all_results['polish'].append(r)
    print(f"  [{MODEL_A}] latency={r['model_a']['latency_ms']}ms tokens={r['model_a']['prompt_tokens']}+{r['model_a']['completion_tokens']}")
    if r['model_a']['content']:
        print(f"    -> {r['model_a']['content'][:200]}")
    if r['model_a']['error']:
        print(f"    ERROR: {r['model_a']['error']}")
    print(f"  [{MODEL_B}] latency={r['model_b']['latency_ms']}ms tokens={r['model_b']['prompt_tokens']}+{r['model_b']['completion_tokens']}")
    if r['model_b']['content']:
        print(f"    -> {r['model_b']['content'][:200]}")
    if r['model_b']['error']:
        print(f"    ERROR: {r['model_b']['error']}")

# === Summary ===
print("\n" + "=" * 80)
print("Summary")
print("=" * 80)

for task_name, task_results in [("叙事生成", all_results['narrative']), ("答案润色", all_results['polish'])]:
    print(f"\n--- {task_name} ---")
    a_latencies = [r['model_a']['latency_ms'] for r in task_results if not r['model_a']['error']]
    b_latencies = [r['model_b']['latency_ms'] for r in task_results if not r['model_b']['error']]
    a_tokens = sum(r['model_a']['prompt_tokens'] + r['model_a']['completion_tokens'] for r in task_results if not r['model_a']['error'])
    b_tokens = sum(r['model_b']['prompt_tokens'] + r['model_b']['completion_tokens'] for r in task_results if not r['model_b']['error'])
    a_fail = sum(1 for r in task_results if r['model_a']['error'])
    b_fail = sum(1 for r in task_results if r['model_b']['error'])
    a_safe = sum(1 for r in task_results if r.get('model_a', {}).get('safe_check', {}).get('passed'))
    b_safe = sum(1 for r in task_results if r.get('model_b', {}).get('safe_check', {}).get('passed'))
    total = len(task_results)

    if a_latencies:
        a_latencies.sort()
        a_p50 = a_latencies[len(a_latencies) // 2]
        a_p95 = a_latencies[int(len(a_latencies) * 0.95)]
        print(f"  [{MODEL_A}] samples={len(a_latencies)}/{total} latency_p50={a_p50}ms p95={a_p95}ms total_tokens={a_tokens} fail={a_fail}")
    if b_latencies:
        b_latencies.sort()
        b_p50 = b_latencies[len(b_latencies) // 2]
        b_p95 = b_latencies[int(len(b_latencies) * 0.95)]
        print(f"  [{MODEL_B}] samples={len(b_latencies)}/{total} latency_p50={b_p50}ms p95={b_p95}ms total_tokens={b_tokens} fail={b_fail}")
    if task_name == "叙事生成":
        print(f"  safe_check pass: [{MODEL_A}] {a_safe}/{total}  [{MODEL_B}] {b_safe}/{total}")

# Save results
with open('narrative_ab_results.json', 'w', encoding='utf-8') as f:
    json.dump(all_results, f, ensure_ascii=False, indent=2)
print(f"\nResults saved to narrative_ab_results.json")