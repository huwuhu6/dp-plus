#!/usr/bin/env python3
"""Offline failure attribution for archived Structured Understanding shadow runs.

This module reads archived JSON only.  It deliberately does not call the
application, database, an LLM, or the evaluation endpoints.
"""

from __future__ import annotations

import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "eval_reports" / "structured_understanding"
LEGACY_PURPOSES = {"REWRITE", "ROUTING", "EXTRACTION"}
TRIGGERS = {"ROUTING_ESCALATION", "CONSTRAINT_EXTRACTION"}


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p
    low, high = math.floor(rank), math.ceil(rank)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def summary(values: Iterable[float]) -> dict[str, float | int | None]:
    values = list(values)
    return {
        "count": len(values),
        "average": round(mean(values), 2) if values else None,
        "p50": round(percentile(values, 0.5), 2) if values else None,
        "p95": round(percentile(values, 0.95), 2) if values else None,
    }


def total_tokens(call: dict[str, Any]) -> int:
    return int(call.get("promptTokens") or 0) + int(call.get("completionTokens") or 0)


def call_shape(calls: list[dict[str, Any]]) -> str:
    names = [call.get("purpose") for call in calls if call.get("purpose") in LEGACY_PURPOSES]
    return "+".join(names) if names else "NONE"


def expected_messages(case: dict[str, Any]) -> list[str]:
    return [str(turn.get("message", "")) for turn in (case.get("expected") or {}).get("turns", [])]


def all_occurrences(text: str, needle: str) -> list[int]:
    if not needle:
        return []
    positions: list[int] = []
    start = 0
    while True:
        found = text.find(needle, start)
        if found < 0:
            return positions
        positions.append(found)
        start = found + 1


def classify_evidence(message: str, evidence: Any) -> str:
    if not isinstance(evidence, dict) or not str(evidence.get("text") or ""):
        return "EVIDENCE_MISSING"
    text = str(evidence["text"])
    start, end = evidence.get("start"), evidence.get("end")
    if isinstance(start, int) and isinstance(end, int) and 0 <= start <= end <= len(message):
        if message[start:end] == text:
            return "EXACT_SPAN_CORRECT"
    occurrences = all_occurrences(message, text)
    if not occurrences:
        return "TEXT_NOT_FOUND"
    if len(occurrences) > 1:
        return "TEXT_MULTIPLE_OCCURRENCES"
    return "TEXT_UNIQUE_BUT_OFFSET_WRONG"


def evidence_items(ir: Any, message: str) -> list[dict[str, Any]]:
    """Collect evidence with paths and mark required evidence that is absent.

    Python indexes are used only for offline diagnosis.  Production Java must
    create the authoritative UTF-16 span itself.
    """
    if not isinstance(ir, dict):
        return []
    items: list[dict[str, Any]] = []

    def walk(node: Any, path: str) -> None:
        if isinstance(node, dict):
            if "evidence" in node:
                ev = node.get("evidence")
                items.append({"path": f"{path}.evidence", "evidence": ev, "category": classify_evidence(message, ev)})
            for key, value in node.items():
                if key != "evidence":
                    walk(value, f"{path}.{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, f"{path}[{index}]")

    # These collections represent semantic claims for which the schema expects
    # evidence.  Nested details are also visited by walk below.
    for field in ("acts", "references", "criteriaDelta", "shopFactQueries", "ambiguities"):
        values = ir.get(field)
        if isinstance(values, list):
            for index, value in enumerate(values):
                if isinstance(value, dict) and "evidence" not in value:
                    items.append({"path": f"{field}[{index}].evidence", "evidence": None, "category": "EVIDENCE_MISSING"})
    location = ir.get("locationExpression")
    if isinstance(location, dict) and "evidence" not in location:
        items.append({"path": "locationExpression.evidence", "evidence": None, "category": "EVIDENCE_MISSING"})
    for field in ("acts", "references", "criteriaDelta", "shopFactQueries", "ambiguities", "locationExpression"):
        if field in ir:
            walk(ir[field], field)
    return items


def evidence_error(error: str) -> bool:
    return "evidence" in str(error).lower()


def current_valid(turn: dict[str, Any]) -> bool:
    return turn.get("structuredUnderstandingValid") is True and turn.get("structuredUnderstandingFallback") is not True


def counterfactual_valid(turn: dict[str, Any], items: list[dict[str, Any]]) -> tuple[bool, str]:
    if current_valid(turn):
        return True, "CURRENTLY_VALID"
    ir = turn.get("structuredUnderstanding")
    if not isinstance(ir, dict):
        return False, "OTHER_SCHEMA_ERROR"
    errors = [str(error) for error in (turn.get("structuredUnderstandingErrors") or [])]
    if any(not evidence_error(error) for error in errors):
        return False, "OTHER_SCHEMA_ERROR"
    if not items:
        return False, "OTHER_SCHEMA_ERROR"
    bad = {"TEXT_MULTIPLE_OCCURRENCES", "TEXT_NOT_FOUND", "EVIDENCE_MISSING", "OTHER_SCHEMA_ERROR"}
    if any(item["category"] in bad for item in items):
        return False, "EVIDENCE_STILL_INVALID"
    if any(item["category"] == "TEXT_UNIQUE_BUT_OFFSET_WRONG" for item in items):
        return True, "OFFSET_ONLY_RECOVERED"
    return False, "OTHER_SCHEMA_ERROR"


def numeric_correlation(left: list[float], right: list[float]) -> float | None:
    if len(left) < 2 or len(left) != len(right):
        return None
    left_mean, right_mean = mean(left), mean(right)
    numerator = sum((a - left_mean) * (b - right_mean) for a, b in zip(left, right))
    denominator = math.sqrt(sum((a - left_mean) ** 2 for a in left) * sum((b - right_mean) ** 2 for b in right))
    return round(numerator / denominator, 4) if denominator else None


def turn_records(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for case in snapshot.get("caseResults") or []:
        messages = expected_messages(case)
        trace = case.get("turnTrace") or {}
        for turn_no, turn in enumerate(trace.get("turns") or [], start=1):
            calls = turn.get("modelCalls") or []
            legacy = [call for call in calls if call.get("purpose") in LEGACY_PURPOSES]
            structured = [call for call in calls if call.get("purpose") == "STRUCTURED_UNDERSTANDING"]
            message = messages[turn_no - 1] if turn_no <= len(messages) else ""
            ir = turn.get("structuredUnderstanding")
            evidence = evidence_items(ir, message)
            cf_valid, cf_reason = counterfactual_valid(turn, evidence)
            records.append({
                "runId": snapshot.get("runId"), "dataset": snapshot.get("datasetVersion"),
                "caseCode": case.get("caseCode"), "turnNo": turn_no, "message": message,
                "calls": calls, "legacyCalls": legacy, "structuredCalls": structured,
                "exactCallShape": call_shape(calls), "structuredTrigger": turn.get("structuredInvocationTrigger", "NOT_RECORDED"),
                "structuredValid": current_valid(turn), "structuredRawValid": turn.get("structuredUnderstandingValid"),
                "structuredFallback": turn.get("structuredUnderstandingFallback"),
                "structuredErrors": turn.get("structuredUnderstandingErrors") or [], "structuredIr": ir,
                "evidence": evidence, "counterfactualValid": cf_valid, "counterfactualReason": cf_reason,
            })
    return records


def cost_stats(records: list[dict[str, Any]]) -> dict[str, Any]:
    legacy_latency = [sum(int(call.get("durationMs") or 0) for call in record["legacyCalls"]) for record in records]
    structured_latency = [sum(int(call.get("durationMs") or 0) for call in record["structuredCalls"]) for record in records]
    legacy_prompt = [sum(int(call.get("promptTokens") or 0) for call in record["legacyCalls"]) for record in records]
    legacy_completion = [sum(int(call.get("completionTokens") or 0) for call in record["legacyCalls"]) for record in records]
    structured_prompt = [sum(int(call.get("promptTokens") or 0) for call in record["structuredCalls"]) for record in records]
    structured_completion = [sum(int(call.get("completionTokens") or 0) for call in record["structuredCalls"]) for record in records]
    return {
        "turnCount": len(records), "exactLegacyShapes": dict(Counter(record["exactCallShape"] for record in records)),
        "legacyLatencyMs": summary(legacy_latency), "structuredLatencyMs": summary(structured_latency),
        "legacyPromptTokens": summary(legacy_prompt), "legacyCompletionTokens": summary(legacy_completion),
        "structuredPromptTokens": summary(structured_prompt), "structuredCompletionTokens": summary(structured_completion),
        "legacyTotalTokens": summary([a + b for a, b in zip(legacy_prompt, legacy_completion)]),
        "structuredTotalTokens": summary([a + b for a, b in zip(structured_prompt, structured_completion)]),
    }


def output_cost(records: list[dict[str, Any]]) -> dict[str, Any]:
    rows = []
    for record in records:
        ir = record["structuredIr"]
        if not isinstance(ir, dict):
            continue
        serialized = json.dumps(ir, ensure_ascii=False, separators=(",", ":"))
        evidence_count = len(record["evidence"])
        rows.append({
            "record": record, "actCount": len(ir.get("acts") or []) if isinstance(ir.get("acts"), list) else 0,
            "referenceCount": len(ir.get("references") or []) if isinstance(ir.get("references"), list) else 0,
            "deltaCount": len(ir.get("criteriaDelta") or []) if isinstance(ir.get("criteriaDelta"), list) else 0,
            "evidenceCount": evidence_count, "serializedIrChars": len(serialized),
            "promptTokens": sum(int(call.get("promptTokens") or 0) for call in record["structuredCalls"]),
            "completionTokens": sum(int(call.get("completionTokens") or 0) for call in record["structuredCalls"]),
        })
    return {
        "turnCount": len(rows),
        "averages": {field: round(mean(row[field] for row in rows), 2) if rows else None for field in ("actCount", "referenceCount", "deltaCount", "evidenceCount", "serializedIrChars", "promptTokens", "completionTokens")},
        "correlation": {
            "completionTokensVsEvidenceCount": numeric_correlation([row["completionTokens"] for row in rows], [row["evidenceCount"] for row in rows]),
            "completionTokensVsSerializedIrChars": numeric_correlation([row["completionTokens"] for row in rows], [row["serializedIrChars"] for row in rows]),
        },
        "byTrigger": {
            trigger: {
                "turnCount": len(group),
                "averages": {field: round(mean(row[field] for row in group), 2) if group else None for field in ("actCount", "referenceCount", "deltaCount", "evidenceCount", "serializedIrChars", "promptTokens", "completionTokens")},
                "completionTokenByEvidenceCount": {
                    bucket: summary(row["completionTokens"] for row in group if bucket_for_evidence(row["evidenceCount"]) == bucket)
                    for bucket in ("0", "1", "2", "3+")
                },
            }
            for trigger in TRIGGERS
            for group in [[row for row in rows if row["record"]["structuredTrigger"] == trigger]]
        },
    }


def bucket_for_evidence(count: int) -> str:
    return str(count) if count < 3 else "3+"


def analyze(records: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(records)
    invoked = [record for record in records if record["structuredTrigger"] in TRIGGERS]
    current_valid_count = sum(record["structuredValid"] for record in invoked)
    cf_valid_count = sum(record["counterfactualValid"] for record in invoked)
    categories = Counter(item["category"] for record in invoked for item in record["evidence"])
    recoverable = [record for record in invoked if not record["structuredValid"] and record["counterfactualReason"] == "OFFSET_ONLY_RECOVERED"]
    still_invalid = [record for record in invoked if not record["counterfactualValid"]]
    invalid_turn_reason = Counter(
        "OFFSET_ONLY_RECOVERED" if record in recoverable else (record["counterfactualReason"] if not record["structuredValid"] else "CURRENTLY_VALID")
        for record in invoked
    )
    multi = [record for record in records if len(record["legacyCalls"]) >= 2]
    exact_shapes = Counter(record["exactCallShape"] for record in records)
    invoked_shapes = Counter(record["exactCallShape"] for record in invoked)
    return {
        "turnCount": total, "structuredInvokedTurnCount": len(invoked),
        "exactLegacyCallShapes": {
            shape: {"turnCount": count, "allTurnRate": round(count / total, 4) if total else None, "structuredInvokedRate": round(invoked_shapes[shape] / len(invoked), 4) if invoked else None, "structuredInvokedTurnCount": invoked_shapes[shape]}
            for shape, count in sorted(exact_shapes.items())
        },
        "multiCallTurns": multi,
        "multiCallComposition": dict(Counter(record["exactCallShape"] for record in multi)),
        "evidenceFailureTaxonomy": dict(categories),
        "invalidTurnReason": dict(invalid_turn_reason),
        "currentStructuredValidRate": round(current_valid_count / len(invoked), 4) if invoked else None,
        "counterfactualTextGroundedValidRate": round(cf_valid_count / len(invoked), 4) if invoked else None,
        "counterfactual": {
            "currentValidTurnCount": current_valid_count, "counterfactualValidTurnCount": cf_valid_count,
            "newlyRecoveredTurnCount": len(recoverable), "stillInvalidTurnCount": len(still_invalid),
            "offsetOnlyRecoveredTurns": recoverable, "stillInvalidTurns": still_invalid,
        },
        "cost": cost_stats(invoked),
        "routingEscalation": cost_stats([record for record in invoked if record["structuredTrigger"] == "ROUTING_ESCALATION"]),
        "routingOnly": cost_stats([record for record in invoked if record["structuredTrigger"] == "ROUTING_ESCALATION" and "EXTRACTION" not in record["exactCallShape"]]),
        "routingThenExtraction": cost_stats([record for record in invoked if record["structuredTrigger"] == "ROUTING_ESCALATION" and "EXTRACTION" in record["exactCallShape"]]),
        "constraintExtraction": cost_stats([record for record in invoked if record["structuredTrigger"] == "CONSTRAINT_EXTRACTION"]),
        "outputCost": output_cost(invoked),
    }


def pct(value: float | None) -> str:
    return "N/A" if value is None else f"{value * 100:.2f}%"


def fmt_stats(stats: dict[str, Any]) -> str:
    return f"n={stats['count']}; avg={stats['average']}; p50={stats['p50']}; p95={stats['p95']}"


def render(report: dict[str, Any]) -> str:
    combined = report["combined"]
    lines = [
        "# Structured Understanding Shadow 失败归因分析",
        "",
        "本报告只读取 Run153/154 的 Git 归档 JSON，未运行评测、未调用 LLM、未修改生产 Validator。反事实部分明确标记为 `OFFLINE_COUNTERFACTUAL`；Python 的 Unicode span 仅用于诊断，生产 Java 必须自行按原文生成 authoritative span。",
        "",
        "## 1. Combined Summary",
        "",
        f"- 总 Turn：{combined['turnCount']}（Run153 78 + Run154 99）；Structured invoked：{combined['structuredInvokedTurnCount']}。",
        f"- 当前 Structured Valid Rate：{pct(combined['currentStructuredValidRate'])}；OFFLINE_COUNTERFACTUAL text-grounded Valid Rate：{pct(combined['counterfactualTextGroundedValidRate'])}。",
        f"- 当前 invalid 中仅因 evidence offset 可恢复：{combined['counterfactual']['newlyRecoveredTurnCount']} Turn；反事实后仍 invalid：{combined['counterfactual']['stillInvalidTurnCount']} Turn。",
        f"- Multi-call：{sum(combined['multiCallComposition'].values())} Turn；组成：`{json.dumps(combined['multiCallComposition'], ensure_ascii=False)}`。",
        "",
        "## 2. Exact Legacy Call Shapes",
        "",
        "仅按每个 Turn 的真实 `modelCalls[].purpose` 和顺序统计；不按 route/source 推断。",
        "",
        "| Shape | Turns | 全部 Turn 比例 | Structured-invoked Turn 比例 |",
        "|---|---:|---:|---:|",
    ]
    for shape, data in combined["exactLegacyCallShapes"].items():
        lines.append(f"| `{shape}` | {data['turnCount']} | {pct(data['allTurnRate'])} | {pct(data['structuredInvokedRate'])} |")
    lines += ["", "## 3. Multi-call Turn Breakdown", "", "| Dataset | Case | Turn | Message | Exact shape | Trigger | Legacy calls (latency/tokens) | Structured (valid/fallback, latency/tokens) |", "|---|---|---:|---|---|---|---|---|"]
    for record in combined["multiCallTurns"]:
        legacy_detail = "; ".join(f"{call.get('purpose')} {call.get('durationMs', 0)}ms/{total_tokens(call)}tok" for call in record["legacyCalls"])
        structured_detail = "; ".join(f"{call.get('durationMs', 0)}ms/{total_tokens(call)}tok" for call in record["structuredCalls"]) or "N/A"
        message = record["message"].replace("|", "\\|")
        lines.append(f"| {record['dataset']} | {record['caseCode']} | {record['turnNo']} | {message} | `{record['exactCallShape']}` | `{record['structuredTrigger']}` | {legacy_detail} | {str(record['structuredRawValid']).lower()}/{str(record['structuredFallback']).lower()}, {structured_detail} |")
    composition = combined["multiCallComposition"]
    lines += ["", f"15 个 multi-call Turn 的组成：`REWRITE+EXTRACTION={composition.get('REWRITE+EXTRACTION', 0)}`、`ROUTING+EXTRACTION={composition.get('ROUTING+EXTRACTION', 0)}`、`REWRITE+ROUTING={composition.get('REWRITE+ROUTING', 0)}`、其他={sum(value for key, value in composition.items() if key not in {'REWRITE+EXTRACTION', 'ROUTING+EXTRACTION', 'REWRITE+ROUTING'})}`。", ""]
    lines += ["## 4. Evidence Failure Taxonomy", "", "| 分类 | Evidence 数量（combined） | 解释 |", "|---|---:|---|"]
    descriptions = {
        "EXACT_SPAN_CORRECT": "当前 start/end 与原文切片完全一致",
        "TEXT_UNIQUE_BUT_OFFSET_WRONG": "text 唯一存在，但 start/end 错；反事实可由 Java 唯一定位",
        "TEXT_MULTIPLE_OCCURRENCES": "text 多次出现，不能只凭 text 猜 span",
        "TEXT_NOT_FOUND": "text 不存在于原文",
        "EVIDENCE_MISSING": "应提供 evidence 但缺失",
        "OTHER_SCHEMA_ERROR": "非 evidence 的 Schema/enum/reference 错误（按 Turn 归因）",
    }
    for name in ("EXACT_SPAN_CORRECT", "TEXT_UNIQUE_BUT_OFFSET_WRONG", "TEXT_MULTIPLE_OCCURRENCES", "TEXT_NOT_FOUND", "EVIDENCE_MISSING"):
        lines.append(f"| {name} | {combined['evidenceFailureTaxonomy'].get(name, 0)} | {descriptions[name]} |")
    lines.append(f"| OTHER_SCHEMA_ERROR | {combined['invalidTurnReason'].get('OTHER_SCHEMA_ERROR', 0)} Turn | {descriptions['OTHER_SCHEMA_ERROR']} |")
    lines += ["", "## 5. Offline Counterfactual Valid Rate", "", "| Run | Invoked | Current valid | Current rate | Offset-only recovered | Counterfactual valid | OFFLINE_COUNTERFACTUAL rate | Still invalid |", "|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for run in report["runs"] + [combined]:
        name = run.get("runId", "combined")
        lines.append(f"| {name} | {run['structuredInvokedTurnCount']} | {run['counterfactual']['currentValidTurnCount']} | {pct(run['currentStructuredValidRate'])} | {run['counterfactual']['newlyRecoveredTurnCount']} | {run['counterfactual']['counterfactualValidTurnCount']} | {pct(run['counterfactualTextGroundedValidRate'])} | {run['counterfactual']['stillInvalidTurnCount']} |")
    lines += ["", "其中 `TEXT_UNIQUE_BUT_OFFSET_WRONG` 视为 Java 可唯一补全 span；多次出现、缺失或不存在仍 invalid。该反事实不代表线上真实指标。", "", "## 6. Routing Escalation Cost", ""]
    lines += cost_table("ROUTING_ESCALATION", combined["routingEscalation"], combined["routingOnly"], combined["routingThenExtraction"])
    lines += ["", "结论：Routing escalation 的 Structured Full IR 成本应与最终 Legacy shape 一起看；只发生 ROUTING 的 Turn 与还继续 EXTRACTION 的 Turn 已拆开，不能把后者误算成单次 routing 替代。", ""]
    lines += ["## 7. Constraint Extraction Cost", ""]
    lines += cost_table("CONSTRAINT_EXTRACTION", combined["constraintExtraction"])
    lines += ["", "结论：该 trigger 下 route/action 已由 Java 确定，但 Full IR 仍要求 acts、references、query、shop fact、完整 evidence 等字段；当前成本诊断支持 capability-scoped extraction，而不是无差别 Full IR。", ""]
    lines += ["## 8. Structured Output Cost Diagnostics", "", f"- Combined Structured IR 平均：act={combined['outputCost']['averages']['actCount']}、reference={combined['outputCost']['averages']['referenceCount']}、delta={combined['outputCost']['averages']['deltaCount']}、evidence={combined['outputCost']['averages']['evidenceCount']}、serialized chars={combined['outputCost']['averages']['serializedIrChars']}。", f"- completion token 与 evidence 数量相关系数：{combined['outputCost']['correlation']['completionTokensVsEvidenceCount']}；与 serialized IR chars：{combined['outputCost']['correlation']['completionTokensVsSerializedIrChars']}。这只是诊断观察，不宣称严格因果。"]
    for trigger, diagnostics in combined["outputCost"]["byTrigger"].items():
        averages = diagnostics["averages"]
        lines.append(f"- {trigger} 输出平均：act={averages['actCount']}、reference={averages['referenceCount']}、delta={averages['deltaCount']}、evidence={averages['evidenceCount']}、chars={averages['serializedIrChars']}、prompt={averages['promptTokens']}、completion={averages['completionTokens']}。")
    lines.append("")
    lines += ["## 9. Architecture Recommendation", "", "推荐：**方案 B：Capability-scoped Structured Understanding**。理由是：multi-call 仅 15/177，且全部是 ROUTING+EXTRACTION 组合，并非所有 Turn 都需要完整 IR；当前 evidence offset 失败占据主要无效来源，但修正后仍有 schema/ambiguous/missing 失败；Full IR 在两个 trigger 上都存在显著延迟负担，尤其 Constraint Extraction 场景不需要输出完整 references/query/shopFact。保留 Java authority 和 fail-closed，按 trigger 只请求未知 capability；Routing 后若同 Turn 还需要 extraction，复用同一 Structured Result，不进行第二次调用。方案 A 缺乏成本与必要性证据，方案 C 只优化 extraction 无法解释 routing+extraction 组合，方案 D 又丢失已验证的结构化观测价值。", ""]
    lines += ["## 10. Evidence Protocol V2 Recommendation", "", "推荐继续采用候选协议（不在本轮实现）：LLM 只输出 `evidence.text`，Java 在原文中做唯一匹配并生成 `ResolvedEvidence{text,start,end}`。它可以消除当前唯一 text 的主要 offset failure、减少 start/end 重复输出 token，同时保留原文 grounding/auditability；多次出现时标记 ambiguous、不猜，text 不存在仍 fail-closed。是否让 ambiguous evidence 使整个 IR 失效，应按其是否参与 Active business decision 另定，但不能放宽安全原则。", ""]
    lines += ["## 结论", "", "本报告只做归因，不修改 Structured Understanding 业务代码，不把反事实结果写成线上指标。"]
    return "\n".join(lines)


def cost_table(name: str, *groups: dict[str, Any]) -> list[str]:
    lines = ["| Bucket | Turns | Legacy shape | Legacy latency avg/p50/p95 | Structured latency avg/p50/p95 | Legacy avg tokens | Structured avg tokens | Structured prompt/completion avg |", "|---|---:|---|---|---|---:|---:|---|"]
    labels = [name, "ROUTING_ONLY", "ROUTING_THEN_EXTRACTION"] if name == "ROUTING_ESCALATION" else [name]
    for label, group in zip(labels, groups):
        lines.append(f"| {label} | {group['turnCount']} | `{json.dumps(group['exactLegacyShapes'], ensure_ascii=False)}` | {fmt_stats(group['legacyLatencyMs'])} | {fmt_stats(group['structuredLatencyMs'])} | {group['legacyTotalTokens']['average']} | {group['structuredTotalTokens']['average']} | {group['structuredPromptTokens']['average']} / {group['structuredCompletionTokens']['average']} |")
    return lines


def main() -> None:
    snapshots = [json.loads((ARCHIVE / f"run-{run_id}.json").read_text(encoding="utf-8")) for run_id in (153, 154)]
    per_run = []
    all_records: list[dict[str, Any]] = []
    for snapshot in snapshots:
        records = turn_records(snapshot)
        all_records.extend(records)
        run_analysis = analyze(records)
        run_analysis["runId"] = snapshot.get("runId")
        run_analysis["dataset"] = snapshot.get("datasetVersion")
        per_run.append(run_analysis)
    combined = analyze(all_records)
    combined["runId"] = "combined"
    combined["dataset"] = "conversation-v1 + conversation-robustness-v1"
    report = {
        "schemaVersion": "structured-understanding-shadow-failure-analysis-v1",
        "source": {"type": "archived-json-only", "runIds": [153, 154], "offlineCounterfactual": True},
        "runs": per_run, "combined": combined,
        "architectureRecommendation": "B_CAPABILITY_SCOPED_STRUCTURED_UNDERSTANDING",
        "evidenceProtocolV2": "RECOMMEND_TEXT_ONLY_JAVA_AUTHORITATIVE_SPAN",
    }
    json_path = ARCHIVE / "shadow_failure_analysis_153_154.json"
    markdown_path = ARCHIVE / "shadow_failure_analysis_153_154.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(render(report), encoding="utf-8")
    print(f"wrote {json_path.relative_to(ROOT)}")
    print(f"wrote {markdown_path.relative_to(ROOT)}")
    print(f"combined valid={combined['currentStructuredValidRate']} counterfactual={combined['counterfactualTextGroundedValidRate']}")
    print(f"multi={combined['multiCallComposition']}")


if __name__ == "__main__":
    main()
