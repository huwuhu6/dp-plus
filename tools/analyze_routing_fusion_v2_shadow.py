#!/usr/bin/env python3
"""Offline report for Routing Fusion V2 shadow runs.

This script intentionally reads archived JSON only.  It does not call the
application, database, model provider, or evaluation endpoints.
"""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from pathlib import Path
from statistics import mean
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "eval_reports" / "structured_understanding"
SEMANTIC = {"REWRITE", "ROUTING", "EXTRACTION"}
V2_PURPOSE = "STRUCTURED_UNDERSTANDING"
ACTIVE_ACTS = {"REQUEST_RECOMMENDATION", "MUTATE_CRITERIA", "EXPLORE_ALTERNATIVE"}
FIELDS = {
    "CUISINE", "KEYWORD", "ARRIVAL_TIME", "EXCLUDED_CUISINE", "PREFERENCE",
    "BUDGET_PER_PERSON", "RADIUS_KM", "NEARBY",
}
OPS = {"SET", "CLEAR", "ADD", "REMOVE", "INCREASE", "DECREASE"}


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p
    low, high = math.floor(rank), math.ceil(rank)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def summary(values: Iterable[float]) -> dict[str, Any]:
    numbers = list(values)
    return {
        "count": len(numbers),
        "average": round(mean(numbers), 2) if numbers else None,
        "p50": round(percentile(numbers, 0.50), 2) if numbers else None,
        "p95": round(percentile(numbers, 0.95), 2) if numbers else None,
    }


def delta(current: float | int | None, previous: float | int | None) -> dict[str, float | None]:
    if not isinstance(current, (int, float)) or not isinstance(previous, (int, float)):
        return {"absolute": None, "percent": None}
    return {
        "absolute": current - previous,
        "percent": round((current - previous) / previous * 100, 2) if previous else None,
    }


def load(run_id: int) -> dict[str, Any]:
    path = ARCHIVE / f"run-{run_id}.json"
    if not path.exists():
        raise SystemExit(f"archive not found: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def metric(metrics: dict[str, Any], name: str) -> Any:
    aliases = {
        "complete": ("complete", "completedCount", "completeCount"),
        "route": ("route", "routeMatchedCount"),
        "tool": ("tool", "toolMatchedCount"),
        "final": ("final", "finalStatusMatchedCount"),
        "locality": ("locality", "localityMatchedCount"),
        "memory": ("memory", "memoryMatchedCount"),
    }
    for key in aliases[name]:
        if key in metrics:
            return metrics[key]
    return None


def message_for(case: dict[str, Any], turn_no: int) -> str:
    turns = (case.get("expected") or {}).get("turns") or []
    if 0 < turn_no <= len(turns):
        return str(turns[turn_no - 1].get("message") or "")
    return ""


def turns(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for case in snapshot.get("caseResults") or []:
        for number, turn in enumerate((case.get("turnTrace") or {}).get("turns") or [], 1):
            calls = turn.get("modelCalls") or []
            semantic = [call for call in calls if call.get("purpose") in SEMANTIC]
            shape = "+".join(str(call.get("purpose")) for call in semantic) if semantic else "NONE"
            structured = [call for call in calls if call.get("purpose") == V2_PURPOSE]
            ir = turn.get("routingFusionV2")
            stages = turn.get("stages") or []
            criteria = {}
            for stage in stages:
                if stage.get("node") == "CriteriaReductionNode":
                    criteria = ((stage.get("metadata") or {}).get("criteria") or {})
            result.append({
                "runId": snapshot.get("runId"),
                "datasetVersion": snapshot.get("datasetVersion"),
                "caseCode": case.get("caseCode"),
                "turn": number,
                "message": message_for(case, number),
                "route": turn.get("route"),
                "decisionStatus": turn.get("decisionStatus"),
                "shape": shape,
                "legacyCalls": semantic,
                "structuredCalls": structured,
                "structuredInvoked": bool(turn.get("structuredInvoked")),
                "trigger": turn.get("structuredInvocationTrigger", "NOT_RECORDED"),
                "structuredValid": turn.get("routingFusionV2Valid"),
                "structuredFallback": turn.get("routingFusionV2Fallback"),
                "criteriaReusable": turn.get("routingFusionV2CriteriaReusable"),
                "errors": turn.get("routingFusionV2Errors") or [],
                "ir": ir,
                "criteria": criteria,
            })
    return result


def effective_location_present(ir: dict[str, Any] | None) -> bool:
    """Mirror the V2 gate after the service's empty-option normalization.

    An empty rawText/evidence object with reset=false is normalized away.  A
    reset=true expression remains a location authority and is rejected by the
    current adapter gate.
    """
    location = (ir or {}).get("locationExpression")
    if location is None:
        return False
    return bool(location.get("rawText") or location.get("evidenceText") or location.get("reset"))


def offline_active_gate(turn: dict[str, Any]) -> tuple[bool, str]:
    if not turn["structuredInvoked"]:
        return False, "not_invoked"
    if turn["structuredValid"] is not True:
        return False, "invalid"
    if turn["criteriaReusable"] is not True:
        return False, "criteria_not_reusable"
    ir = turn["ir"] or {}
    if effective_location_present(ir):
        return False, "location_expression_present"
    ambiguities = ir.get("ambiguities")
    if ambiguities:
        return False, "ambiguities"
    acts = ir.get("acts")
    if not acts:
        return False, "acts_empty"
    for act in acts:
        if not isinstance(act, dict) or act.get("type") not in ACTIVE_ACTS:
            return False, "unsupported_act"
    deltas = ir.get("criteriaDelta")
    if deltas is None:
        return False, "criteria_delta_missing"
    has_mutation = any(act.get("type") == "MUTATE_CRITERIA" for act in acts if isinstance(act, dict))
    if not deltas and has_mutation:
        return False, "mutation_without_delta"
    for item in deltas:
        if not isinstance(item, dict) or item.get("field") not in FIELDS or item.get("operation") not in OPS:
            return False, "unsupported_delta"
        field, op = item["field"], item["operation"]
        if field in {"CUISINE", "KEYWORD", "ARRIVAL_TIME"} and op not in {"SET", "CLEAR"}:
            return False, "unsupported_delta"
        if field == "EXCLUDED_CUISINE" and op not in {"ADD", "SET"}:
            return False, "unsupported_delta"
        if field == "PREFERENCE" and op not in {"ADD", "REMOVE", "CLEAR"}:
            return False, "unsupported_delta"
        if field in {"BUDGET_PER_PERSON", "RADIUS_KM"}:
            if op not in {"SET", "INCREASE", "DECREASE"}:
                return False, "unsupported_delta"
            value = str(item.get("rawValue") or "")
            if op == "SET":
                try:
                    if float("".join(ch for ch in value if ch.isdigit() or ch == ".")) < 0:
                        return False, "invalid_absolute_number"
                except ValueError:
                    return False, "invalid_absolute_number"
            elif any(ch.isdigit() for ch in value):
                return False, "relative_numeric_magnitude"
        if field == "NEARBY" and op not in {"SET", "CLEAR"}:
            return False, "unsupported_delta"
    return True, "eligible"


def evidence_status(message: str, text: Any) -> str:
    if not isinstance(text, str) or not text.strip():
        return "MISSING"
    count = message.count(text)
    if count == 0:
        return "NOT_FOUND"
    if count > 1:
        return "AMBIGUOUS"
    return "RESOLVED"


def evidence_items(turn: dict[str, Any]) -> list[dict[str, Any]]:
    ir = turn.get("ir") or {}
    values: list[tuple[str, Any]] = []
    for index, act in enumerate(ir.get("acts") or []):
        values.append((f"acts[{index}].evidenceText", (act or {}).get("evidenceText") if isinstance(act, dict) else None))
    for index, item in enumerate(ir.get("criteriaDelta") or []):
        values.append((f"criteriaDelta[{index}].evidenceText", (item or {}).get("evidenceText") if isinstance(item, dict) else None))
    location = ir.get("locationExpression")
    if location is not None:
        values.append(("locationExpression.evidenceText", location.get("evidenceText") if isinstance(location, dict) else None))
    return [{"path": path, "text": text, "status": evidence_status(turn["message"], text)} for path, text in values]


def error_category(error: str) -> str:
    value = str(error)
    lowered = value.lower()
    if "evidence" in lowered or "evidencetext" in lowered:
        if value.startswith("acts"):
            return "act evidenceText grounding"
        if value.startswith("criteriaDelta"):
            return "criteria evidenceText grounding"
        if value.startswith("locationExpression"):
            return "location evidenceText grounding"
        return "evidence grounding"
    if "missing" in lowered or value.endswith(".text"):
        return "missing evidence"
    if "ambigu" in lowered:
        return "ambiguous evidence"
    if "not_found" in lowered or "notfound" in lowered:
        return "not found"
    if any(term in lowered for term in ("version", "type", "field", "operation", "acts", "criteria", "ambiguities")):
        return "schema/enum"
    return "other"


def call_cost(calls: list[dict[str, Any]]) -> tuple[int, int]:
    return (
        sum(int(call.get("durationMs") or 0) for call in calls),
        sum(int(call.get("promptTokens") or 0) + int(call.get("completionTokens") or 0) for call in calls),
    )


def paired(turn_list: list[dict[str, Any]], shape: str) -> dict[str, Any]:
    group = [turn for turn in turn_list if turn["structuredInvoked"] and turn["shape"] == shape]
    legacy_latency, structured_latency, legacy_tokens, structured_tokens = [], [], [], []
    for turn in group:
        ll, lt = call_cost(turn["legacyCalls"])
        sl, st = call_cost(turn["structuredCalls"])
        legacy_latency.append(ll); structured_latency.append(sl)
        legacy_tokens.append(lt); structured_tokens.append(st)
    llat, slat, ltok, stok = map(summary, (legacy_latency, structured_latency, legacy_tokens, structured_tokens))
    return {
        "turnCount": len(group), "legacyLatencyMs": llat, "structuredLatencyMs": slat,
        "legacyTokens": ltok, "structuredTokens": stok,
        "latencyAverageDelta": delta(slat["average"], llat["average"]),
        "tokenAverageDelta": delta(stok["average"], ltok["average"]),
        "turns": [{"caseCode": t["caseCode"], "turn": t["turn"]} for t in group],
    }


def review_rows(turn_list: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for turn in turn_list:
        if not turn["structuredInvoked"] or turn["shape"] != "ROUTING+EXTRACTION":
            continue
        eligible, reason = offline_active_gate(turn)
        rows.append({
            "caseCode": turn["caseCode"], "turn": turn["turn"], "message": turn["message"],
            "legacyRoute": turn["route"],
            "legacyCriteria": {key: turn["criteria"].get(key) for key in (
                "keyword", "cuisine", "targetProvince", "targetCity", "targetDistrict",
                "budgetPerPerson", "radiusKm", "nearby", "preferences", "excludedCuisines") if key in turn["criteria"]},
            "v2Acts": (turn["ir"] or {}).get("acts"),
            "criteriaDelta": (turn["ir"] or {}).get("criteriaDelta"),
            "locationExpression": (turn["ir"] or {}).get("locationExpression"),
            "valid": turn["structuredValid"], "criteriaReusable": turn["criteriaReusable"],
            "offlineActiveEligible": eligible, "eligibilityReason": reason,
            "review": "NEEDS_MANUAL_REVIEW",
        })
    return rows


def analyze_run(snapshot: dict[str, Any]) -> dict[str, Any]:
    run_turns = turns(snapshot)
    invoked = [t for t in run_turns if t["structuredInvoked"]]
    trigger = Counter(t["trigger"] for t in run_turns)
    shapes = Counter(t["shape"] for t in run_turns)
    evidence = Counter()
    for turn in invoked:
        evidence.update(item["status"] for item in evidence_items(turn))
    errors = Counter(category for turn in invoked for category in (error_category(e) for e in turn["errors"]))
    eligible_rows = []
    reasons = Counter()
    for turn in invoked:
        ok, reason = offline_active_gate(turn)
        reasons[reason] += 1
        if ok:
            eligible_rows.append({"caseCode": turn["caseCode"], "turn": turn["turn"]})
    trigger_violations = [t for t in invoked if t["trigger"] == "CONSTRAINT_EXTRACTION"]
    extraction_shape_violations = [t for t in run_turns if t["shape"] == "EXTRACTION" and t["structuredInvoked"]]
    multi_structured = [t for t in run_turns if len(t["structuredCalls"]) > 1]
    start_end = []
    for turn in invoked:
        raw = json.dumps(turn["ir"], ensure_ascii=False) + json.dumps(turn["errors"], ensure_ascii=False)
        if '"start"' in raw or '"end"' in raw or "start/end" in raw:
            start_end.append({"caseCode": turn["caseCode"], "turn": turn["turn"]})
    metrics = snapshot.get("metrics") or {}
    return {
        "run": {
            "runId": snapshot.get("runId"), "datasetVersion": snapshot.get("datasetVersion"),
            "experimentMode": snapshot.get("experimentMode"), "branch": snapshot.get("branch"),
            "git": snapshot.get("git"), "models": snapshot.get("models"),
            "caseCount": len(snapshot.get("caseResults") or []), "turnCount": len(run_turns), "metrics": metrics,
        },
        "invocation": {
            "invoked": len(invoked), "notInvoked": len(run_turns) - len(invoked),
            "rate": round(len(invoked) / len(run_turns), 4) if run_turns else None,
            "triggerDistribution": dict(trigger), "triggerViolationCount": len(trigger_violations),
            "triggerViolationTurns": [{"caseCode": t["caseCode"], "turn": t["turn"]} for t in trigger_violations],
            "legacyCallShape": dict(shapes),
            "extractionOnlyStructuredViolations": [{"caseCode": t["caseCode"], "turn": t["turn"]} for t in extraction_shape_violations],
            "multiStructuredCallCount": len(multi_structured),
            "multiStructuredCalls": [{"caseCode": t["caseCode"], "turn": t["turn"]} for t in multi_structured],
        },
        "v2": {
            "valid": sum(t["structuredValid"] is True for t in invoked),
            "fallbackOrInvalid": sum(t["structuredValid"] is not True or t["structuredFallback"] is True for t in invoked),
            "validRate": round(sum(t["structuredValid"] is True for t in invoked) / len(invoked), 4) if invoked else None,
            "criteriaReusable": sum(t["criteriaReusable"] is True for t in invoked),
            "validationErrorCategories": dict(errors),
            "startEndEvidenceMentions": start_end,
            "evidenceStatus": dict(evidence),
            "activeEligibility": {
                "valid": sum(t["structuredValid"] is True for t in invoked),
                "criteriaReusable": sum(t["criteriaReusable"] is True for t in invoked),
                "offlineEligible": len(eligible_rows),
                "rejectReasons": dict(reasons), "eligibleTurns": eligible_rows,
            },
        },
        "pairedCost": {
            "ROUTING": paired(run_turns, "ROUTING"),
            "ROUTING+EXTRACTION": paired(run_turns, "ROUTING+EXTRACTION"),
        },
        "criteriaFusionReview": review_rows(run_turns),
        "turns": run_turns,
    }


def compare(current: dict[str, Any], baseline: dict[str, Any]) -> dict[str, Any]:
    cm, bm = current["run"]["metrics"], baseline.get("metrics") or {}
    return {
        "currentRunId": current["run"]["runId"], "baselineRunId": baseline.get("runId"),
        "datasetVersion": current["run"]["datasetVersion"],
        "metrics": {name: {"current": metric(cm, name), "baseline": metric(bm, name),
                            "delta": delta(metric(cm, name), metric(bm, name))}
                     for name in ("complete", "route", "tool", "final", "locality", "memory")},
    }


def render(report: dict[str, Any]) -> str:
    lines = [
        "# Routing Fusion V2 Shadow 离线分析（Run155/156）", "",
        "本报告只读取 Git 归档 JSON，不访问应用、数据库、HTTP、模型或重新运行评测。V2 仍是 Shadow 旁路，没有业务 authority。Python evidence 统计仅用于离线诊断，生产 evidence authority 仍是 Java `EvidenceGrounder`。", "",
        "## 实验元数据", "",
        "| Run | Dataset | Mode | Cases | Turns | Commit at run | Branch at archive |", "|---:|---|---|---:|---:|---|---|",
    ]
    for item in report["runs"]:
        r = item["run"]
        lines.append(f"| {r['runId']} | {r['datasetVersion']} | {r['experimentMode']} | {r['caseCount']} | {r['turnCount']} | `{(r.get('git') or {}).get('atRun')}` | `{(r.get('branch') or {}).get('atArchive')}` |")
    lines += ["", "## 调用范围与 V2 Funnel", ""]
    for item in report["runs"]:
        r, inv, v2 = item["run"], item["invocation"], item["v2"]
        lines += [
            f"### Run {r['runId']}（{r['datasetVersion']}）", "",
            f"- V2 invoked：**{inv['invoked']}/{r['turnCount']}（{inv['rate']}）**；NOT_INVOKED={inv['notInvoked']}。trigger={json.dumps(inv['triggerDistribution'], ensure_ascii=False)}。",
            f"- `CONSTRAINT_EXTRACTION` trigger violation：**{inv['triggerViolationCount']}**；extraction-only shape 误调用 V2：**{len(inv['extractionOnlyStructuredViolations'])}**；同 Turn >1 次 V2：**{inv['multiStructuredCallCount']}**。",
            f"- Legacy shape：`{json.dumps(inv['legacyCallShape'], ensure_ascii=False)}`。",
            f"- V2 valid/fallback：**{v2['valid']}/{v2['fallbackOrInvalid']}**，valid rate={v2['validRate']}；criteriaReusable={v2['criteriaReusable']}。",
            f"- Active eligibility（离线等价 gate）：valid={v2['activeEligibility']['valid']}，reusable={v2['activeEligibility']['criteriaReusable']}，offline eligible={v2['activeEligibility']['offlineEligible']}；reject={json.dumps(v2['activeEligibility']['rejectReasons'], ensure_ascii=False)}。",
            f"- evidence status（离线 `message.count(text)` 诊断）：`{json.dumps(v2['evidenceStatus'], ensure_ascii=False)}`；start/end 提交或错误痕迹：{len(v2['startEndEvidenceMentions'])}。",
            f"- validation 分类：`{json.dumps(v2['validationErrorCategories'], ensure_ascii=False)}`。",
        ]
    lines += ["", "## Paired cost（仅同 Turn、同一 Shadow 观测）", "", "| Run | Bucket | Turns | Legacy latency avg/p50/p95 | V2 latency avg/p50/p95 | latency Δ | Legacy tokens avg/p50/p95 | V2 tokens avg/p50/p95 | tokens Δ |", "|---:|---|---:|---|---|---|---|---|---|"]
    for item in report["runs"]:
        for bucket, values in item["pairedCost"].items():
            lines.append(f"| {item['run']['runId']} | {bucket} | {values['turnCount']} | {values['legacyLatencyMs']['average']}/{values['legacyLatencyMs']['p50']}/{values['legacyLatencyMs']['p95']} | {values['structuredLatencyMs']['average']}/{values['structuredLatencyMs']['p50']}/{values['structuredLatencyMs']['p95']} | {values['latencyAverageDelta']} | {values['legacyTokens']['average']}/{values['legacyTokens']['p50']}/{values['legacyTokens']['p95']} | {values['structuredTokens']['average']}/{values['structuredTokens']['p50']}/{values['structuredTokens']['p95']} | {values['tokenAverageDelta']} |")
    lines += ["", "## E2E safety observation", "", "不同运行之间只作安全观察，不作因果归因。"]
    for comp in report["comparisons"]:
        lines.append(f"- Run {comp['currentRunId']} vs Run {comp['baselineRunId']}（同 dataset）：" + ", ".join(f"{k} Δ={v['delta']['absolute']}" for k, v in comp["metrics"].items() if v["delta"]["absolute"] is not None) + ".")
    lines += ["", "## Criteria Fusion 人工复核（全部 ROUTING+EXTRACTION 且 V2 invoked）", "", "以下不是 gold label；每一行都标为 `NEEDS_MANUAL_REVIEW`，重点核对 legacy criteria 与 V2 delta 是否表达同一语义。"]
    rows = [row for item in report["runs"] for row in item["criteriaFusionReview"]]
    lines += ["", f"共 {len(rows)} 条；JSON 保存完整字段。优先复核前 {min(10, len(rows))} 条：", "", "| Case | Turn | Legacy route | Message | V2 acts | Delta | Valid/Reusable | Offline active |", "|---|---:|---|---|---|---|---|---|"]
    for row in rows[:10]:
        lines.append(f"| {row['caseCode']} | {row['turn']} | {row['legacyRoute']} | {row['message'].replace('|', '/')} | {json.dumps(row['v2Acts'], ensure_ascii=False)} | {json.dumps(row['criteriaDelta'], ensure_ascii=False)} | {row['valid']}/{row['criteriaReusable']} | {row['offlineActiveEligible']} |")
    lines += ["", "## 决策", "", f"**{report['decision']}**。本次仅验证 Shadow 观测契约；未运行 Active 或 holdout。", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", type=int, action="append", default=[155, 156])
    parser.add_argument("--output-prefix", default="routing_fusion_v2_shadow_155_156")
    args = parser.parse_args()
    run_ids = list(dict.fromkeys(args.run_id))
    analyzed = [analyze_run(load(run_id)) for run_id in run_ids]
    baseline_map = {run_id: load(run_id) for run_id in (147, 148, 151, 154)}
    comparisons = []
    for item in analyzed:
        same_shadow = 153 if item["run"]["datasetVersion"] == "conversation-v1" else 154
        off = 148 if item["run"]["datasetVersion"] == "conversation-v1" else 147
        comparisons.append({"type": "shadow", **compare(item, load(same_shadow))})
        comparisons.append({"type": "off", **compare(item, load(off))})
    invoked = sum(item["v2"]["valid"] + item["v2"]["fallbackOrInvalid"] for item in analyzed)
    valid = sum(item["v2"]["valid"] for item in analyzed)
    no_trigger_violation = all(item["invocation"]["triggerViolationCount"] == 0 for item in analyzed)
    no_multi = all(item["invocation"]["multiStructuredCallCount"] == 0 for item in analyzed)
    no_extraction_violation = all(not item["invocation"]["extractionOnlyStructuredViolations"] for item in analyzed)
    valid_enough = (valid / invoked) >= 0.8 if invoked else False
    routing_cost_ok = all(
        item["pairedCost"]["ROUTING"]["latencyAverageDelta"]["absolute"] is not None
        and item["pairedCost"]["ROUTING"]["latencyAverageDelta"]["absolute"] <= 0
        for item in analyzed
        if item["pairedCost"]["ROUTING"]["turnCount"]
    )
    decision = "GO_TO_V2_ACTIVE" if no_trigger_violation and no_multi and no_extraction_violation and valid_enough and routing_cost_ok else "STOP_ROUTING_FUSION"
    report = {
        "schemaVersion": "routing-fusion-v2-shadow-analysis-v1",
        "source": {"type": "archived-json-only", "runIds": run_ids},
        "runs": analyzed, "comparisons": comparisons, "decision": decision,
        "decisionChecks": {
            "noConstraintExtractionTriggerViolation": no_trigger_violation,
            "noMoreThanOneStructuredCallPerTurn": no_multi,
            "noExtractionOnlyStructuredViolation": no_extraction_violation,
            "validRateAtLeast80Percent": valid_enough,
            "routingPairedLatencyNotWorse": routing_cost_ok,
        },
    }
    (ARCHIVE / f"{args.output_prefix}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ARCHIVE / f"{args.output_prefix}.md").write_text(render(report), encoding="utf-8")
    print(f"wrote eval_reports/structured_understanding/{args.output_prefix}.json")
    print(f"wrote eval_reports/structured_understanding/{args.output_prefix}.md")
    print(f"decision={decision} invoked={invoked} valid={valid}")


if __name__ == "__main__":
    main()
