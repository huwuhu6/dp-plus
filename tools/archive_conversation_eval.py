#!/usr/bin/env python3
"""Persist conversation evaluation runs as versioned, redacted snapshots.

The normal source is the read-only conversation evaluation HTTP API.  A MySQL
source is also provided for archiving historical runs when the application is
not running; it only performs SELECTs and never submits an evaluation.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "eval_reports" / "structured_understanding"


def _git(*args: str) -> str:
    try:
        return subprocess.run(
            ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
        ).stdout.strip()
    except Exception:
        return ""


def _camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def _camelize(value: Any) -> Any:
    if isinstance(value, (dt.datetime, dt.date)):
        return value.isoformat()
    # pymysql returns DECIMAL columns as Decimal; keep metric precision in JSON.
    if value.__class__.__name__ == "Decimal":
        return float(value)
    if isinstance(value, dict):
        return {_camel(str(k)): _camelize(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_camelize(v) for v in value]
    return value


_SECRET_KEY = re.compile(
    r"(?:^|_)(?:api[_-]?key|authorization|cookie|password|secret|access[_-]?token|refresh[_-]?token)(?:$|_)",
    re.I,
)
_SECRET_VALUE = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_QUERY_SECRET = re.compile(r"(?i)([?&](?:key|api_key|token|password)=)[^&\s]+")


def redact(value: Any, key: str = "") -> Any:
    """Redact transport credentials without removing token *counts*."""
    if _SECRET_KEY.search(key):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {k: redact(v, str(k)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, key) for v in value]
    if isinstance(value, str):
        return _QUERY_SECRET.sub(r"\1[REDACTED]", _SECRET_VALUE.sub("Bearer [REDACTED]", value))
    return value


def parse_json(value: Any) -> Any:
    if value is None or value == "":
        return None
    if isinstance(value, (dict, list)):
        return value
    try:
        return json.loads(value)
    except (TypeError, ValueError):
        return None


def _unwrap(body: Any) -> Dict[str, Any]:
    if isinstance(body, dict) and isinstance(body.get("data"), dict):
        return body["data"]
    if not isinstance(body, dict):
        raise RuntimeError("评测 API 返回格式不是 JSON object")
    return body


def load_dataset(dataset_version: str) -> Dict[int, Dict[str, Any]]:
    path = ROOT / "src" / "main" / "resources" / "eval" / "datasets" / f"{dataset_version}.jsonl"
    if not path.exists():
        return {}
    result: Dict[int, Dict[str, Any]] = {}
    index = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        item = json.loads(line)
        if item.get("active") is False:
            continue
        index += 1
        result[index] = item
    return result


def fetch_http(base_url: str, run_id: int) -> tuple[Dict[str, Any], Dict[str, Any]]:
    from urllib.request import Request, urlopen

    def get(path: str) -> Dict[str, Any]:
        request = Request(base_url.rstrip("/") + path, headers={"Accept": "application/json"})
        with urlopen(request, timeout=30) as response:
            return _unwrap(json.loads(response.read().decode("utf-8")))

    run = get(f"/ai/evaluations/conversation-runs/{run_id}")
    diagnostics = get(f"/ai/evaluations/conversation-runs/{run_id}/diagnostics")
    return run, diagnostics


def fetch_mysql(args: argparse.Namespace, run_id: int) -> tuple[Dict[str, Any], Dict[str, Any]]:
    try:
        import pymysql
    except ImportError as exc:
        raise RuntimeError("MySQL source requires the optional pymysql package") from exc

    password = args.mysql_password or os.environ.get(args.mysql_password_env, "")
    if not password:
        raise RuntimeError(f"请通过 --mysql-password 或 ${args.mysql_password_env} 提供数据库密码")
    connection = pymysql.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=password,
        database=args.mysql_database,
        cursorclass=pymysql.cursors.DictCursor,
        charset="utf8mb4",
        read_timeout=30,
        write_timeout=30,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM tbl_ai_conversation_evaluation_run WHERE id=%s", (run_id,))
            run = cursor.fetchone()
            if not run:
                raise RuntimeError(f"评测 Run {run_id} 不存在")
            cursor.execute(
                "SELECT * FROM tbl_ai_conversation_evaluation_case_result WHERE run_id=%s ORDER BY id",
                (run_id,),
            )
            cases = cursor.fetchall()
            diagnostics = {
                "run": run,
                "failureCounts": _db_failure_counts(cases),
                "failures": _db_failures(cases),
                "source": "mysql_read_only",
            }
            return {"run": run, "caseResults": cases}, diagnostics
    finally:
        connection.close()


def _db_failure_counts(cases: Iterable[Dict[str, Any]]) -> Dict[str, int]:
    rows = list(cases)
    checks = {
        "route": "route_matched",
        "contextRewrite": "context_rewrite_matched",
        "toolCoverage": "tool_matched",
        "toolArguments": "tool_arguments_matched",
        "locality": "locality_matched",
        "finalStatus": "final_status_matched",
        "shop": "shop_matched",
        "recovery": "recovery_matched",
        "workingMemory": "memory_matched",
        "unseenRecommendations": "unseen_recommendations_matched",
    }
    return {
        key: sum(1 for row in rows if row.get(column) in (0, False))
        for key, column in checks.items()
    }


def _db_failures(cases: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    failures = []
    for row in cases:
        if not any(row.get(k) in (0, False) for k in (
            "route_matched", "tool_matched", "locality_matched", "final_status_matched",
            "shop_matched", "recovery_matched", "memory_matched", "unseen_recommendations_matched",
        )):
            continue
        failures.append({
            "caseId": row.get("case_id"),
            "actualRoutesJson": row.get("actual_routes_json"),
            "actualToolNamesJson": row.get("actual_tool_names_json"),
            "actualToolCallsJson": row.get("actual_tool_calls_json"),
            "actualFinalStatus": row.get("actual_final_status"),
            "routeMatched": row.get("route_matched"),
            "toolMatched": row.get("tool_matched"),
            "localityMatched": row.get("locality_matched"),
            "finalStatusMatched": row.get("final_status_matched"),
            "memoryMatched": row.get("memory_matched"),
            "turnAssertionFailures": _turn_failures(row.get("turn_outputs_json")),
            "errorMessage": row.get("error_message"),
        })
    return failures


def _turn_failures(raw: Any) -> List[Dict[str, Any]]:
    trace = parse_json(raw)
    if not isinstance(trace, dict) or not isinstance(trace.get("assertionFailures"), list):
        return []
    return trace["assertionFailures"]


def _case_trace(case: Dict[str, Any]) -> tuple[Any, str]:
    raw = case.get("turnOutputsJson", case.get("turn_outputs_json"))
    trace = parse_json(raw)
    if trace is None:
        return None, "unavailable: persisted turn_outputs_json is absent or invalid"
    return trace, "available"


def build_archive(run_payload: Dict[str, Any], diagnostics_payload: Dict[str, Any], mode: str,
                  source: str, archive_branch: str, archive_git: str, run_id: int) -> Dict[str, Any]:
    run = _camelize(run_payload.get("run", run_payload))
    cases = run_payload.get("caseResults", [])
    cases = _camelize(cases)
    dataset_version = run.get("datasetVersion") or "unavailable"
    dataset = load_dataset(dataset_version)
    archived_cases = []
    trace_available = 0
    trace_unavailable = 0
    for case in cases:
        case_id = case.get("caseId")
        trace, trace_status = _case_trace(case)
        if trace is None:
            trace_unavailable += 1
        else:
            trace_available += 1
        expected = dataset.get(int(case_id)) if str(case_id).isdigit() else None
        archived_cases.append({
            "caseId": case_id,
            "caseCode": expected.get("caseCode") if expected else None,
            "notes": expected.get("notes") if expected else None,
            "expected": redact(expected) if expected else {
                "status": "unavailable",
                "reason": "dataset JSONL was not available while archiving",
            },
            "result": redact(case),
            "turnTrace": redact(trace),
            "turnTraceStatus": trace_status,
        })

    diagnostics = redact(_camelize(diagnostics_payload))
    run_recorded_git = run.get("gitCommit")
    archive_dirty = archive_git.endswith("-dirty")
    return {
        "schemaVersion": "structured-understanding-eval-archive-v1",
        "archivedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": source,
        "runId": run_id,
        "datasetVersion": dataset_version,
        "experimentMode": mode,
        "branch": {
            "atRun": None,
            "atRunStatus": "unavailable: run table has no branch column",
            "atArchive": archive_branch or None,
        },
        "git": {
            "atRun": run_recorded_git,
            "atRunStatus": "recorded by evaluation service" if run_recorded_git else "unavailable",
            "atArchive": archive_git or None,
            "archiveDirty": archive_dirty,
        },
        "models": {
            "primary": run.get("model"),
            "routing": {"value": None, "status": "unavailable: not persisted in run metadata"},
            "rewrite": {"value": None, "status": "unavailable: not persisted in run metadata"},
            "structuredUnderstanding": {"value": None, "status": "unavailable: not persisted in run metadata"},
        },
        "execution": {
            "caseCount": run.get("caseCount"),
            "runCreatedAt": run.get("createTime"),
            "status": run.get("status"),
            "errorSummary": run.get("errorSummary"),
        },
        "metrics": {
            "complete": run.get("completedCount"),
            "route": run.get("routeMatchedCount"),
            "tool": run.get("toolMatchedCount"),
            "final": run.get("finalStatusMatchedCount"),
            "locality": run.get("localityMatchedCount"),
            "contextRewrite": {
                "expected": run.get("contextRewriteExpectedCount"),
                "matched": run.get("contextRewriteMatchedCount"),
            },
            "memory": {"status": "available per case/turn where persisted"},
            "errorRate": run.get("errorRate"),
            "modelCallCount": run.get("modelCallCount"),
            "modelSuccessCount": run.get("modelSuccessCount"),
            "modelFailureCount": run.get("modelFailureCount"),
            "promptTokens": run.get("promptTokenCount"),
            "completionTokens": run.get("completionTokenCount"),
            "durationMs": {
                "avg": run.get("avgDurationMs"),
                "p50": run.get("p50DurationMs"),
                "p95": run.get("p95DurationMs"),
                "p99": run.get("p99DurationMs"),
            },
        },
        "traceAvailability": {
            "caseResults": "available",
            "turnTraceAvailableCases": trace_available,
            "turnTraceUnavailableCases": trace_unavailable,
            "modelCalls": "available inside turnTrace when persisted",
            "structuredIR": "available inside turnTrace when persisted",
            "stageLatencyMs": "available inside turnTrace.stages when persisted",
        },
        "diagnostics": diagnostics,
        "caseResults": archived_cases,
    }


def manifest_entry(archive: Dict[str, Any]) -> Dict[str, Any]:
    metrics = archive["metrics"]
    return {
        "runId": archive["runId"],
        "datasetVersion": archive["datasetVersion"],
        "experimentMode": archive["experimentMode"],
        "branchAtRun": archive["branch"]["atRun"],
        "archiveBranch": archive["branch"]["atArchive"],
        "gitAtRun": archive["git"]["atRun"],
        "caseCount": archive["execution"]["caseCount"],
        "complete": metrics["complete"],
        "route": metrics["route"],
        "tool": metrics["tool"],
        "final": metrics["final"],
        "locality": metrics["locality"],
        "memory": archive["metrics"]["memory"],
        "turnTraceAvailableCases": archive["traceAvailability"]["turnTraceAvailableCases"],
        "turnTraceUnavailableCases": archive["traceAvailability"]["turnTraceUnavailableCases"],
        "status": archive["execution"]["status"],
        "archivedAt": archive["archivedAt"],
    }


def write_archive(archive: Dict[str, Any], output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    run_id = archive["runId"]
    run_path = output_dir / f"run-{run_id}.json"
    run_path.write_text(json.dumps(archive, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    manifest_path = output_dir / "manifest.jsonl"
    entries: Dict[int, Dict[str, Any]] = {}
    if manifest_path.exists():
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            item = json.loads(line)
            if "runId" in item:
                entries[int(item["runId"])] = item
    entries[run_id] = manifest_entry(archive)
    manifest_path.write_text(
        "".join(json.dumps(entries[k], ensure_ascii=False, separators=(",", ":")) + "\n"
                for k in sorted(entries)),
        encoding="utf-8",
    )
    _write_summary(output_dir, list(entries.values()))
    return run_path


def _write_summary(output_dir: Path, entries: List[Dict[str, Any]]) -> None:
    lines = [
        "# Structured Understanding Evaluation Archive",
        "",
        "这些文件是评测 Run 的只读快照，不依赖数据库长期保留历史结果。原始逐轮 trace 位于对应 `run-<id>.json`。",
        "",
        "| Run | Dataset | Mode | Cases | Complete | Route | Tool | Final | Locality | Trace |",
        "|---:|---|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for item in sorted(entries, key=lambda x: int(x["runId"])):
        trace = f"{item.get('turnTraceAvailableCases', 0)}/{item.get('caseCount', '?')}"
        lines.append(
            f"| {item['runId']} | {item.get('datasetVersion', '')} | {item.get('experimentMode', '')} | "
            f"{item.get('caseCount', '')} | {item.get('complete', '')} | {item.get('route', '')} | "
            f"{item.get('tool', '')} | {item.get('final', '')} | {item.get('locality', '')} | {trace} |"
        )
    lines += [
        "",
        "Unavailable fields are explicitly marked in each JSON snapshot; no value is inferred from a missing API/DB field.",
    ]
    (output_dir / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Archive a completed conversation evaluation Run")
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--mode", choices=("off", "shadow", "active"), required=True)
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--source", choices=("http", "mysql"), default="http")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-database", default="hmdp")
    parser.add_argument("--mysql-password-env", default="EVAL_ARCHIVE_DB_PASSWORD")
    parser.add_argument("--mysql-password")
    args = parser.parse_args(argv)

    branch = _git("branch", "--show-current")
    git_sha = _git("describe", "--always", "--dirty")
    if args.source == "http":
        run_payload, diagnostics_payload = fetch_http(args.base_url, args.run_id)
    else:
        run_payload, diagnostics_payload = fetch_mysql(args, args.run_id)
    archive = build_archive(run_payload, diagnostics_payload, args.mode, args.source, branch, git_sha, args.run_id)
    path = write_archive(redact(archive), args.output_dir)
    print(json.dumps({"runId": args.run_id, "archive": str(path), "source": args.source}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
