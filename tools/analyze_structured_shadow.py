#!/usr/bin/env python3
"""Offline analysis for archived Structured Understanding shadow runs.

The script reads only ``eval_reports/structured_understanding/run-*.json``.
It intentionally performs no HTTP, database, model, or application calls.
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
SEMANTIC_PURPOSES = {"REWRITE", "ROUTING", "EXTRACTION"}
REVIEW_TERMS = {
    "relative": ("更便宜", "更近", "便宜点", "近点"),
    "clear_remove": ("不要", "不用", "去掉", "取消", "不限定"),
    "compound": ("，", "但是", "同时", "还有"),
    "reference": ("第一家", "第二家", "第三家", "这家", "刚才", "最开始"),
    "shop_fact": ("营业", "地址", "插座", "优惠", "评价", "券"),
    "decision_context": ("为什么推荐", "根据什么", "我说过", "预算是我"),
    "location": ("附近", "位置", "城市", "区", "大学", "学校"),
    "alternative": ("换一批", "其他", "别的", "随便"),
    "ambiguous": ("哪个", "哪里", "那个", "这家"),
}


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    rank = (len(values) - 1) * p
    low, high = math.floor(rank), math.ceil(rank)
    if low == high:
        return values[low]
    return values[low] + (values[high] - values[low]) * (rank - low)


def numeric_summary(values: Iterable[float]) -> dict[str, float | int | None]:
    numbers = list(values)
    return {
        "count": len(numbers),
        "average": round(mean(numbers), 2) if numbers else None,
        "p50": round(percentile(numbers, 0.50), 2) if numbers else None,
        "p95": round(percentile(numbers, 0.95), 2) if numbers else None,
    }


def get_turns(case: dict[str, Any]) -> list[dict[str, Any]]:
    trace = case.get("turnTrace") or {}
    return trace.get("turns") or []


def expected_messages(case: dict[str, Any]) -> list[str]:
    return [str(turn.get("message", "")) for turn in (case.get("expected") or {}).get("turns", [])]


def call_summary(calls: list[dict[str, Any]]) -> tuple[int, int, int, list[dict[str, Any]]]:
    legacy = [call for call in calls if call.get("purpose") in SEMANTIC_PURPOSES]
    structured = [call for call in calls if call.get("purpose") == "STRUCTURED_UNDERSTANDING"]
    return (
        len(legacy),
        sum(int(call.get("durationMs") or 0) for call in legacy),
        sum(int(call.get("promptTokens") or 0) + int(call.get("completionTokens") or 0) for call in legacy),
        structured,
    )


def relative_delta(current: float | int | None, baseline: float | int | None) -> dict[str, float | None]:
    if not isinstance(current, (int, float)) or not isinstance(baseline, (int, float)):
        return {"absolute": None, "percent": None}
    return {
        "absolute": current - baseline,
        "percent": round((current - baseline) / baseline * 100, 2) if baseline else None,
    }


def flag_categories(message: str) -> list[str]:
    return [name for name, terms in REVIEW_TERMS.items() if any(term in message for term in terms)]


def analyze_run(snapshot: dict[str, Any]) -> dict[str, Any]:
    cases = snapshot.get("caseResults") or []
    all_turns: list[dict[str, Any]] = []
    for case in cases:
        messages = expected_messages(case)
        for index, turn in enumerate(get_turns(case), start=1):
            calls = turn.get("modelCalls") or []
            legacy_count, legacy_latency, legacy_tokens, structured_calls = call_summary(calls)
            structured_latency = sum(int(call.get("durationMs") or 0) for call in structured_calls)
            structured_tokens = sum(
                int(call.get("promptTokens") or 0) + int(call.get("completionTokens") or 0)
                for call in structured_calls
            )
            all_turns.append(
                {
                    "dataset": snapshot.get("datasetVersion"),
                    "runId": snapshot.get("runId"),
                    "caseCode": case.get("caseCode"),
                    "turn": index,
                    "message": messages[index - 1] if index <= len(messages) else "",
                    "route": turn.get("route"),
                    "legacyCallCount": legacy_count,
                    "legacyLatencyMs": legacy_latency,
                    "legacyTokens": legacy_tokens,
                    "structuredInvoked": bool(turn.get("structuredInvoked")),
                    "structuredTrigger": turn.get("structuredInvocationTrigger", "NOT_RECORDED"),
                    "structuredApplied": bool(turn.get("structuredApplied")),
                    "structuredValid": turn.get("structuredUnderstandingValid"),
                    "structuredFallback": turn.get("structuredUnderstandingFallback"),
                    "structuredErrors": turn.get("structuredUnderstandingErrors") or [],
                    "structuredIr": turn.get("structuredUnderstanding"),
                    "structuredCallCount": len(structured_calls),
                    "structuredLatencyMs": structured_latency,
                    "structuredTokens": structured_tokens,
                    "modelCalls": calls,
                    "assertionFailures": (case.get("turnTrace") or {}).get("assertionFailures") or [],
                }
            )

    shapes = Counter(str(turn["legacyCallCount"]) for turn in all_turns)
    trigger_distribution = Counter(turn["structuredTrigger"] for turn in all_turns)
    invoked = [turn for turn in all_turns if turn["structuredInvoked"]]
    invalid_or_fallback = [
        turn for turn in invoked if turn["structuredValid"] is False or turn["structuredFallback"] is True
    ]
    multi_legacy = [turn for turn in all_turns if turn["legacyCallCount"] >= 2]
    applied = [turn for turn in all_turns if turn["structuredApplied"]]
    over_one_structured = [turn for turn in all_turns if turn["structuredCallCount"] > 1]

    paired_groups: dict[str, list[dict[str, Any]]] = {
        "all_structured_invoked": invoked,
        "legacy_one_call": [turn for turn in invoked if turn["legacyCallCount"] == 1],
        "legacy_two_or_more_calls": [turn for turn in invoked if turn["legacyCallCount"] >= 2],
    }
    paired: dict[str, Any] = {}
    for name, group in paired_groups.items():
        legacy_latency = numeric_summary(turn["legacyLatencyMs"] for turn in group)
        structured_latency = numeric_summary(turn["structuredLatencyMs"] for turn in group)
        legacy_tokens = numeric_summary(turn["legacyTokens"] for turn in group)
        structured_tokens = numeric_summary(turn["structuredTokens"] for turn in group)
        paired[name] = {
            "turnCount": len(group),
            "legacyLatencyMs": legacy_latency,
            "structuredLatencyMs": structured_latency,
            "latencyAverageDelta": relative_delta(structured_latency["average"], legacy_latency["average"]),
            "legacyTokens": legacy_tokens,
            "structuredTokens": structured_tokens,
            "tokenAverageDelta": relative_delta(structured_tokens["average"], legacy_tokens["average"]),
        }

    validation_errors = Counter(
        error
        for turn in invalid_or_fallback
        for error in turn["structuredErrors"]
    )
    invoked_legacy_calls = sum(turn["legacyCallCount"] for turn in invoked)

    review_candidates = []
    for turn in all_turns:
        categories = flag_categories(turn["message"])
        if categories:
            review_candidates.append({
                "caseCode": turn["caseCode"], "turn": turn["turn"], "message": turn["message"],
                "categories": categories, "route": turn["route"],
                "structuredInvoked": turn["structuredInvoked"], "structuredValid": turn["structuredValid"],
                "structuredFallback": turn["structuredFallback"], "structuredIr": turn["structuredIr"],
            })

    metrics = snapshot.get("metrics") or {}
    return {
        "run": {
            "runId": snapshot.get("runId"), "datasetVersion": snapshot.get("datasetVersion"),
            "experimentMode": snapshot.get("experimentMode"), "git": snapshot.get("git"),
            "caseCount": len(cases), "turnCount": len(all_turns), "metrics": metrics,
        },
        "legacySemanticCallShape": dict(sorted(shapes.items(), key=lambda item: int(item[0]))),
        "structured": {
            "invokedTurns": len(invoked), "notInvokedTurns": len(all_turns) - len(invoked),
            "invocationRate": round(len(invoked) / len(all_turns), 4) if all_turns else None,
            "triggerDistribution": dict(trigger_distribution),
            "appliedTurnCount": len(applied), "moreThanOneCallTurns": len(over_one_structured),
            "validTurnCount": sum(turn["structuredValid"] is True for turn in invoked),
            "invalidOrFallbackTurnCount": len(invalid_or_fallback),
            "validationErrorDistribution": dict(validation_errors.most_common()),
            "invalidOrFallbackTurns": invalid_or_fallback,
            "theoreticalSemanticCallReduction": {
                "scope": "upper bound only: replace legacy semantic calls on every structured-invoked turn with one structured call",
                "legacyCalls": invoked_legacy_calls,
                "oneStructuredCallPerInvokedTurn": len(invoked),
                "reducedCalls": invoked_legacy_calls - len(invoked),
                "reductionRate": round((invoked_legacy_calls - len(invoked)) / invoked_legacy_calls, 4) if invoked_legacy_calls else None,
            },
        },
        "turnsWithTwoOrMoreLegacySemanticCalls": multi_legacy,
        "withinTurnPairedCost": paired,
        "manualReviewCandidates": review_candidates,
    }


def metric_value(metrics: dict[str, Any], name: str) -> Any:
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


def comparison(shadow: dict[str, Any], baseline: dict[str, Any] | None) -> dict[str, Any]:
    if baseline is None:
        return {"baselineAvailable": False}
    current_metrics = shadow["run"]["metrics"]
    baseline_metrics = baseline["metrics"] or {}
    values = {}
    for name in ("complete", "route", "tool", "final", "locality", "memory"):
        values[name] = {
            "shadow": metric_value(current_metrics, name),
            "baseline": metric_value(baseline_metrics, name),
        }
        values[name]["delta"] = relative_delta(values[name]["shadow"], values[name]["baseline"])
    return {"baselineAvailable": True, "baselineRunId": baseline.get("runId"), "metrics": values}


def render_summary(report: dict[str, Any]) -> str:
    def fmt_summary(summary: dict[str, Any]) -> str:
        return f"avg {summary['average']}; p50 {summary['p50']}; p95 {summary['p95']}"
    lines = [
        "# Structured Understanding Shadow 离线分析",
        "",
        "本报告仅读取已归档的 Run JSON；没有重新运行评测、访问应用/API、数据库或模型。`STRUCTURED_UNDERSTANDING` 为 Shadow 观测调用，以下成本对比是同一 Turn 的观测值；语义调用替代收益仅是 Active 的理论投影，不是 Active 实测。",
        "",
        "## 总览",
        "",
        "| Run | Dataset | Cases | Turns | Complete | Route | Tool | Final | Locality |",
        "|---:|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for run in report["runs"]:
        metrics = run["run"]["metrics"]
        lines.append(
            f"| {run['run']['runId']} | {run['run']['datasetVersion']} | {run['run']['caseCount']} | {run['run']['turnCount']} | "
            f"{metric_value(metrics, 'complete')} | {metric_value(metrics, 'route')} | {metric_value(metrics, 'tool')} | {metric_value(metrics, 'final')} | {metric_value(metrics, 'locality')} |"
        )
    for run in report["runs"]:
        structured = run["structured"]
        lines += [
            "",
            f"## Run {run['run']['runId']}：{run['run']['datasetVersion']}",
            "",
            f"- Legacy semantic-call shape（仅 `REWRITE/ROUTING/EXTRACTION`）：`{json.dumps(run['legacySemanticCallShape'], ensure_ascii=False)}`。",
            f"- Structured：invoked {structured['invokedTurns']}/{run['run']['turnCount']}；trigger `{json.dumps(structured['triggerDistribution'], ensure_ascii=False)}`；applied `{structured['appliedTurnCount']}`（Shadow 必须为 0）；每 Turn >1 次 structured `{structured['moreThanOneCallTurns']}`（必须为 0）。",
            f"- validity：valid {structured['validTurnCount']}；invalid/fallback {structured['invalidOrFallbackTurnCount']}。",
            f"- validation/fallback reason：`{json.dumps(structured['validationErrorDistribution'], ensure_ascii=False)}`。",
            f"- theoretical semantic-call reduction（仅调用次数上限、不是 Active 实测）：legacy {structured['theoreticalSemanticCallReduction']['legacyCalls']} → structured {structured['theoreticalSemanticCallReduction']['oneStructuredCallPerInvokedTurn']}，减少 {structured['theoreticalSemanticCallReduction']['reducedCalls']}（{structured['theoreticalSemanticCallReduction']['reductionRate']}）。",
            "",
            "### 同 Turn 成本（观测；Active 替代为理论投影）",
            "",
            "| Bucket | Turns | Legacy latency ms (avg/p50/p95) | Structured latency ms (avg/p50/p95) | Avg delta | Legacy tokens (avg/p50/p95) | Structured tokens (avg/p50/p95) | Avg delta |",
            "|---|---:|---:|---:|---:|---:|---:|---:|",
        ]
        for name, bucket in run["withinTurnPairedCost"].items():
            latency = bucket["latencyAverageDelta"]
            tokens = bucket["tokenAverageDelta"]
            lines.append(
                f"| {name} | {bucket['turnCount']} | {fmt_summary(bucket['legacyLatencyMs'])} | {fmt_summary(bucket['structuredLatencyMs'])} | "
                f"{latency['absolute']:.2f} ({latency['percent']}%) | {fmt_summary(bucket['legacyTokens'])} | {fmt_summary(bucket['structuredTokens'])} | {tokens['absolute']:.2f} ({tokens['percent']}%) |"
            )
        lines += [
            "",
            f"- 需要人工核对的语义轨迹候选：{len(run['manualReviewCandidates'])}（按消息中相对约束、CLEAR/REMOVE、复合意图、引用、商户事实、状态查询、位置、替代、歧义等词语筛选；不是任一侧的 gold label）。",
            f"- Legacy >=2 semantic calls 的 Turn：{len(run['turnsWithTwoOrMoreLegacySemanticCalls'])}；完整列表在 JSON 的 `turnsWithTwoOrMoreLegacySemanticCalls`。",
            f"- invalid/fallback 详情（含 IR 与 validation errors）在 JSON 的 `structured.invalidOrFallbackTurns`。",
        ]
    lines += [
        "",
        "## 与已归档 off baseline 的安全观察",
        "",
        "这只是不同运行之间的结果对照，不是因果结论；模型与外部 Tool 的波动仍可能影响指标。",
        "",
        "| Shadow Run | Off baseline | Complete Δ | Route Δ | Tool Δ | Final Δ | Locality Δ |",
        "|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for item in report["baselineComparisons"]:
        values = item["comparison"].get("metrics", {})
        delta = lambda key: values.get(key, {}).get("delta", {}).get("absolute", "N/A")
        lines.append(f"| {item['shadowRunId']} | {item['comparison'].get('baselineRunId', 'N/A')} | {delta('complete')} | {delta('route')} | {delta('tool')} | {delta('final')} | {delta('locality')} |")
    lines += [
        "",
        "## 决策",
        "",
        f"`{report['decision']}`。依据见 JSON 的原始计数、逐 Turn paired cost 与人工 review 候选。此决定不把 Shadow 的观测调用当作 Active 收益，也不以单次 E2E 指标宣称因果。",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", type=int, action="append")
    parser.add_argument("--output-prefix", default="shadow_analysis_153_154")
    args = parser.parse_args()
    run_ids = list(dict.fromkeys(args.run_id or [153, 154]))
    snapshots = []
    for run_id in run_ids:
        path = ARCHIVE / f"run-{run_id}.json"
        if not path.exists():
            raise SystemExit(f"archive not found: {path}")
        snapshots.append(json.loads(path.read_text(encoding="utf-8")))
    analyzed = [analyze_run(snapshot) for snapshot in snapshots]
    baseline_by_dataset = {}
    for run_id in (147, 148):
        path = ARCHIVE / f"run-{run_id}.json"
        if path.exists():
            baseline = json.loads(path.read_text(encoding="utf-8"))
            baseline_by_dataset[baseline.get("datasetVersion")] = baseline
    comparisons = [
        {"shadowRunId": run["run"]["runId"], "comparison": comparison(run, baseline_by_dataset.get(run["run"]["datasetVersion"]))}
        for run in analyzed
    ]
    # The current shadow run has no authority.  GO is only justified if its traces
    # prove the contract and there are no aggregate safety regressions vs archived off.
    no_shadow_apply = all(run["structured"]["appliedTurnCount"] == 0 for run in analyzed)
    one_per_turn = all(run["structured"]["moreThanOneCallTurns"] == 0 for run in analyzed)
    complete_deltas = [
        item["comparison"].get("metrics", {}).get("complete", {}).get("delta", {}).get("absolute")
        for item in comparisons
    ]
    no_complete_regression = all(delta is not None and delta >= 0 for delta in complete_deltas)
    # Shadow safely preserves outcomes, but a follow-up Active phase must also be
    # economically and structurally credible.  A systematic invalid/fallback rate
    # near half and higher paired latency make this experiment a stop signal.
    valid_rates = [
        run["structured"]["validTurnCount"] / run["structured"]["invokedTurns"]
        for run in analyzed if run["structured"]["invokedTurns"]
    ]
    paired_latency_not_worse = all(
        run["withinTurnPairedCost"]["all_structured_invoked"]["latencyAverageDelta"]["absolute"] <= 0
        for run in analyzed
    )
    sufficiently_valid = all(rate >= 0.8 for rate in valid_rates)
    decision = (
        "GO_TO_ACTIVE_PURE_CRITERIA"
        if no_shadow_apply and one_per_turn and no_complete_regression and sufficiently_valid and paired_latency_not_worse
        else "STOP_OR_REDESIGN"
    )
    report = {
        "schemaVersion": "structured-understanding-shadow-analysis-v1",
        "source": {"type": "archived-json-only", "runIds": run_ids},
        "runs": analyzed,
        "baselineComparisons": comparisons,
        "decision": decision,
        "decisionChecks": {
            "shadowNeverApplied": no_shadow_apply,
            "structuredAtMostOncePerTurn": one_per_turn,
            "noCompleteRegressionVsArchivedOff": no_complete_regression,
            "structuredValidityAtLeast80Percent": sufficiently_valid,
            "pairedStructuredLatencyNotWorse": paired_latency_not_worse,
        },
    }
    json_path = ARCHIVE / f"{args.output_prefix}.json"
    markdown_path = ARCHIVE / f"{args.output_prefix}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(render_summary(report), encoding="utf-8")
    print(f"wrote {json_path.relative_to(ROOT)}")
    print(f"wrote {markdown_path.relative_to(ROOT)}")
    print(f"decision={decision}")


if __name__ == "__main__":
    main()
