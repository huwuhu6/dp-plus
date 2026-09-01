#!/usr/bin/env python3
"""ConstraintExtractor baseline experiment.
Matches the EXACT Java code behavior:
- No tool_choice (let model decide)
- temperature=0.1
- Same system prompt and function schema as ConstraintExtractor.java

Usage:
    py -3.12 -X utf8 constraint_experiment.py [v1|v2]

    v1: current production prompt (default)
    v2: prompt with few-shot entity boundary instructions
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error

API_KEY = os.environ.get('AI_API_KEY') or os.environ.get('DASHSCOPE_API_KEY') or ''
BASE_URL = os.environ.get('AI_BASE_URL') or 'https://dashscope.aliyuncs.com/compatible-mode/v1'
MODEL = os.environ.get('AI_MODEL') or 'deepseek-v4-flash'

if not API_KEY:
    print("ERROR: No API key found")
    sys.exit(1)

# ---------- Prompt versions ----------
SYSTEM_PROMPT_V1 = (
    "你是餐饮消费决策需求解析器。只能根据用户原话提取约束；"
    "显式目标地点与设备当前位置必须分开："
    "targetCity 是用户要求搜索的城市，"
    "targetArea 是用户要求搜索的行政区、商圈或地标，"
    "不能把它们放进 keyword。"
    "未知值使用空字符串、-1 或 false，不得臆测。"
)

SYSTEM_PROMPT_V2 = (
    '你是餐饮消费决策需求解析器。只能根据用户原话提取约束。\n\n'
    '实体边界规则：\n'
    '1. 菜品名称中的地名（如“重庆鸡公煲”“北京烤鸭”“兰州拉面”）不视为搜索城市，targetCity 保持空字符串。\n'
    '2. 显式目标地点与设备当前位置必须分开：targetCity 是用户要求搜索的城市，targetArea 是用户要求搜索的行政区、商圈或地标，不能把它们放进 keyword。\n'
    '3. 未知值使用空字符串、-1 或 false，不得臆测。\n\n'
    '以下是一些示例（仅作参考，不要硬编码）：\n'
    '- 用户：“帮我找重庆鸡公煲” → targetCity=“”（重庆鸡公煲是菜品名，非搜索城市）\n'
    '- 用户：“我想吃北京烤鸭” → targetCity=“”（北京烤鸭是菜品名，非搜索城市）\n'
    '- 用户：“重庆的鸡公煲” → targetCity=“重庆”（用户明确要求搜索重庆的店铺）\n'
    '- 用户：“在福州找日料店” → targetCity=“福州”（用户明确要求搜索福州的店铺）\n\n'
    '请按这些规则判断，不要将菜品名中的地名当作搜索城市。'
)

# Determine prompt version
prompt_version = "v1"
if len(sys.argv) > 1 and sys.argv[1] == "v2":
    prompt_version = "v2"

SYSTEM_PROMPT = SYSTEM_PROMPT_V2 if prompt_version == "v2" else SYSTEM_PROMPT_V1

# Exact schema from ConstraintExtractor.constraintSchema() line 167-188
PARAM_PROPERTIES = {
    "targetCity": {"type": "string", "description": "Explicit target city requested by the user, for example 重庆 or 福州. Empty string if absent."},
    "targetArea": {"type": "string", "description": "Explicit target district, business area, or landmark, for example 解放碑 or 鼓楼区. Empty string if absent."},
    "locationIntent": {"type": "string", "description": "EXPLICIT_TARGET for a named destination, CURRENT_DEVICE for the user's current location, or UNSPECIFIED."},
    "keyword": {"type": "string", "description": "Restaurant name or core cuisine keyword. Do not include targetCity or targetArea."},
    "cuisine": {"type": "string", "description": "Cuisine, such as 日料. Empty string if unknown."},
    "budgetPerPerson": {"type": "integer", "description": "Maximum per-person budget. -1 if unknown."},
    "radiusKm": {"type": "number", "description": "Search radius in kilometers. -1 if unknown."},
    "nearby": {"type": "boolean", "description": "Whether the user uses a nearby/local intent."},
    "arrivalTime": {"type": "string", "description": "Arrival time HH:mm. Empty string if unknown."},
    "occasion": {"type": "string", "description": "Occasion. Empty string if unknown."},
    "quiet": {"type": "boolean", "description": "Whether quiet ambience is requested."},
    "avoidQueue": {"type": "boolean", "description": "Whether avoiding queues is requested."},
    "hardConstraints": {"type": "array", "items": {"type": "string"}, "description": "Hard constraints explicitly stated by user."},
    "softPreferences": {"type": "array", "items": {"type": "string"}, "description": "Soft preferences explicitly stated by user."},
    "missingInformation": {"type": "array", "items": {"type": "string"}, "description": "Information needed but not supplied."}
}

FUNCTION = {
    "name": "extract_decision_constraints",
    "description": "Extract structured dining or local-consumption decision constraints.",
    "parameters": {
        "type": "object",
        "properties": PARAM_PROPERTIES,
        "required": list(PARAM_PROPERTIES.keys()),
        "additionalProperties": False
    }
}

# Match Java code: tool.put("type", "function"); tool.put("function", function)
TOOL = {"type": "function", "function": FUNCTION}


def call_model(query):
    """Call model with EXACT same parameters as OpenAiCompatibleClient + ConstraintExtractor."""
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": query}
    ]

    body = {
        "model": MODEL,
        "messages": messages,
        "temperature": 0.1,  # Java code line 54: body.put("temperature", 0.1)
        "tools": [TOOL]       # Java code line 56: body.put("tools", tools)
    }
    # Note: No "tool_choice" key — Java code passes null for toolChoice (line 59)

    payload = json.dumps(body, ensure_ascii=False).encode('utf-8')

    url = f"{BASE_URL}/chat/completions"
    req = urllib.request.Request(url, data=payload, method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', f'Bearer {API_KEY}')

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = json.loads(resp.read().decode('utf-8'))
            # Java reads: response.path("choices").path(0).path("message").path("tool_calls").path(0).path("function").path("arguments")
            tc = result['choices'][0]['message'].get('tool_calls')
            if tc and len(tc) > 0:
                args = json.loads(tc[0]['function']['arguments'])
                return args, None
            else:
                return None, "NO_TOOL_CALL: model did not call extract_decision_constraints"
    except Exception as e:
        return None, str(e)


def simulate_clean_retrieval_query(query, constraints):
    # Match ChatOrchestrationService.cleanRetrievalQuery() exactly
    cleaned = query or ""
    tc = constraints.get('targetCity', '') or ''
    ta = constraints.get('targetArea', '') or ''
    kw = constraints.get('keyword', '') or ''
    if tc:
        cleaned = cleaned.replace(tc, ' ')
    if ta:
        cleaned = cleaned.replace(ta, ' ')
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    if cleaned:
        return cleaned
    if kw:
        return kw
    return query


def simulate_semantic_retrieval_query(query, constraints, request_city=None):
    # Match ConsumptionDecisionService.semanticRetrievalQuery() exactly
    q = query or ""
    tc = constraints.get('targetCity', '') or ''
    ta = constraints.get('targetArea', '') or ''
    kw = constraints.get('keyword', '') or ''
    if request_city:
        q = q.replace(request_city, ' ')
    if tc:
        q = q.replace(tc, ' ')
    if ta:
        q = q.replace(ta, ' ')
    q = re.sub(r'\s+', ' ', q).strip()
    if q:
        return q
    if kw:
        return kw
    return ""


# ---------- Test queries ----------
TEST_QUERIES = [
    # Pure city queries
    ("A1", "重庆"),
    ("A2", "福州"),
    # Dish name containing city name (critical)
    ("B1", "重庆鸡公煲"),
    ("B2", "北京烤鸭"),
    ("B3", "兰州拉面"),
    # Explicit city + dish
    ("C1", "重庆的鸡公煲"),
    ("C2", "在重庆找鸡公煲"),
    ("C3", "重庆有什么好吃的"),
    # City + semantic conditions
    ("D1", "重庆适合约会的日料店"),
    ("D2", "福州适合约会的日料店"),
    ("D3", "福州 日料"),
    # Typo variants
    ("E1", "虫情技工包"),
    ("E2", "重庆鸡公保"),
    # District
    ("F1", "鼓楼区适合约会的餐厅"),
    # Additional dish-name-containing-city cases
    ("G1", "沙县小吃"),
    ("G2", "西安肉夹馍"),
    ("G3", "我想吃重庆鸡公煲"),
    ("G4", "帮我找重庆鸡公煲"),
]

HOLDOUT_QUERIES = [
    ("H1", "帮我找北京烤鸭店"),
    ("H2", "我想吃兰州拉面"),
    ("H3", "福州哪里有卖重庆鸡公煲"),
    ("H4", "搜索西安肉夹馍店"),
    ("H5", "重庆鸡公煲和王老吉"),
    ("H6", "找一下广州的重庆鸡公煲"),
    ("H7", "北京烤鸭"),
    ("H8", "帮我搜一下附近有没有重庆鸡公煲"),
]

# Expected targetCity for evaluation (empty string = should NOT be extracted)
EXPECTED_TC = {
    # Pure city queries: should extract city
    "A1": "重庆", "A2": "福州",
    # Dish name queries: should NOT extract city
    "B1": "", "B2": "", "B3": "",
    # Explicit city queries: should extract city
    "C1": "重庆", "C2": "重庆", "C3": "重庆",
    # City + semantic conditions: should extract city
    "D1": "重庆", "D2": "福州", "D3": "福州",
    # Typo: should NOT extract
    "E1": "", "E2": "",
    # District: should NOT extract city (only district "鼓楼区")
    "F1": "",
    # Dish name queries: should NOT extract
    "G1": "", "G2": "", "G3": "", "G4": "",
    # Holdout queries
    "H1": "", "H2": "", "H3": "福州", "H4": "", "H5": "", "H6": "广州", "H7": "", "H8": "",
}

# Expected keyword for evaluation (should retain the dish name, not be stripped)
KW_SHOULD_RETAIN = {
    "B1": True, "B2": True, "B3": True,
    "E1": True, "E2": True,
    "G1": True, "G2": True, "G3": True, "G4": True,
    "H1": True, "H2": True, "H4": True, "H5": True, "H7": True,
}


def run_tests(queries, label=""):
    results = {}
    print(f"\n{'=' * 130}")
    print(f"  {label}")
    print(f"  Model: {MODEL}  |  Base URL: {BASE_URL}")
    print(f"  Temperature: 0.1  |  tool_choice: NOT SET (matches Java code)")
    print(f"  Prompt version: {prompt_version}")
    print(f"{'=' * 130}")
    header = f"{'ID':<6} {'Query':<28} {'targetCity':<14} {'targetArea':<14} {'keyword':<20} {'cuisine':<12} {'cleanRQ':<24} {'semRQ':<24} {'Milvus':<8} {'误过滤':<8}"
    print(header)
    print("-" * 130)

    for qid, query in queries:
        result, error = call_model(query)
        if error:
            print(f"{qid:<6} {query:<28} {'ERROR':<14} {'':<14} {'':<20} {'':<12} {'':<24} {'':<24} {'':<8} {error[:60]:<8}")
            results[qid] = {'query': query, 'error': error}
            continue

        tc = result.get('targetCity', '') or ''
        ta = result.get('targetArea', '') or ''
        kw = result.get('keyword', '') or ''
        cu = result.get('cuisine', '') or ''
        li = result.get('locationIntent', '') or ''

        # Production: request_city = targetCity (from applyLocationSlot line 1210)
        request_city = tc if tc else None

        clean_rq = simulate_clean_retrieval_query(query, result)
        # semanticRetrievalQuery applied on cleanRQ result (production flow)
        sem_rq = simulate_semantic_retrieval_query(clean_rq, result, request_city)

        calls_milvus = "YES" if sem_rq and sem_rq.strip() else "NO"
        wrong_filter = "!!" if tc and tc != "" else "-"

        print(f"{qid:<6} {query:<28} {tc:<14} {ta:<14} {kw:<20} {cu:<12} {clean_rq:<24} {sem_rq:<24} {calls_milvus:<8} {wrong_filter:<8}")

        results[qid] = {
            'query': query,
            'targetCity': tc,
            'targetArea': ta,
            'keyword': kw,
            'cuisine': cu,
            'locationIntent': li,
            'cleanRQ': clean_rq,
            'semRQ': sem_rq,
            'callsMilvus': calls_milvus,
            'wrongFilter': wrong_filter
        }

    print("=" * 130)
    return results


def evaluate(results, queries, label=""):
    """Evaluate targetCity accuracy."""
    tc_correct = 0
    tc_total = 0
    kw_retained = 0
    kw_total = 0
    wrong_filter = 0
    regression = 0

    for qid, query in queries:
        if qid not in results or 'error' in results.get(qid, {}):
            continue
        r = results[qid]
        tc_total += 1
        expected = EXPECTED_TC.get(qid, "")
        actual = r.get('targetCity', '')
        if expected == actual:
            tc_correct += 1
        else:
            wrong_filter += 1
            if qid in EXPECTED_TC and EXPECTED_TC[qid] == "" and actual != "":
                regression += 1

        # Check keyword retention
        if KW_SHOULD_RETAIN.get(qid, False):
            kw_total += 1
            kw = r.get('keyword', '') or ''
            if kw:
                kw_retained += 1

    print(f"\n  [{label}] targetCity 准确率: {tc_correct}/{tc_total} = {tc_correct/tc_total*100:.1f}%")
    if kw_total > 0:
        print(f"  [{label}] keyword 保留率: {kw_retained}/{kw_total} = {kw_retained/kw_total*100:.1f}%")
    print(f"  [{label}] 误提取 targetCity: {wrong_filter} 次")
    print(f"  [{label}] 回归（原本正确→现在错误）: {regression} 次")
    return tc_correct, tc_total, kw_retained, kw_total, wrong_filter, regression


# ========== Main ==========
print("=" * 60)
print(f"ConstraintExtractor Prompt 实验")
print(f"Prompt 版本: {prompt_version}")
print(f"模型: {MODEL}")
print("=" * 60)

# Run baseline 18 queries
results_18 = run_tests(TEST_QUERIES, "Baseline 18 查询")
tc_ok_18, tc_total_18, kw_ok_18, kw_total_18, wf_18, reg_18 = evaluate(results_18, TEST_QUERIES, "Baseline")

# Run holdout queries
results_holdout = run_tests(HOLDOUT_QUERIES, f"Holdout {len(HOLDOUT_QUERIES)} 查询")
tc_ok_h, tc_total_h, kw_ok_h, kw_total_h, wf_h, reg_h = evaluate(results_holdout, HOLDOUT_QUERIES, "Holdout")

# Summary
print("\n" + "=" * 60)
print(f"Summary (Prompt {prompt_version})")
print("=" * 60)
print(f"Baseline 18 查询: targetCity 准确率 {tc_ok_18}/{tc_total_18} ({tc_ok_18/tc_total_18*100:.1f}%)")
print(f"Holdout {len(HOLDOUT_QUERIES)} 查询: targetCity 准确率 {tc_ok_h}/{tc_total_h} ({tc_ok_h/tc_total_h*100:.1f}%)")
print(f"误提取 targetCity: {wf_18 + wf_h} 次 (baseline {wf_18}, holdout {wf_h})")
print(f"回归问题: {reg_18 + reg_h} 次 (baseline {reg_18}, holdout {reg_h})")
print(f"keyword 保留率: {kw_ok_18 + kw_ok_h}/{kw_total_18 + kw_total_h} ({(kw_ok_18 + kw_ok_h)/(kw_total_18 + kw_total_h)*100:.1f}%)" if (kw_total_18 + kw_total_h) > 0 else "")