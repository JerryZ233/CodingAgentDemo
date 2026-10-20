"""Normalize Harbor trials without dropping missing/failed tasks from the denominator."""
import csv
from datetime import datetime
import json
from pathlib import Path


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def phase_ms(phase):
    if not phase or not phase.get("started_at") or not phase.get("finished_at"):
        return None
    try:
        return max(0, round((datetime.fromisoformat(phase["finished_at"].replace("Z", "+00:00")) -
                             datetime.fromisoformat(phase["started_at"].replace("Z", "+00:00"))).total_seconds() * 1000))
    except (ValueError, TypeError, AttributeError):
        return None


def cache_hit_rate(metrics):
    """Unknown/partial accounting must not masquerade as a measured zero."""
    inputs, cached = metrics.get("input_tokens"), metrics.get("cached_tokens")
    if (not metrics.get("usage_complete") or not metrics.get("cache_usage_complete")
            or not isinstance(inputs, (int, float)) or inputs <= 0
            or not isinstance(cached, (int, float)) or not 0 <= cached <= inputs):
        return None
    return cached / inputs


def tool_statistics(directory):
    """Count actual starts/results, preserving interrupted calls as unfinished."""
    empty = dict(tool_call_count=None, tool_success_count=None, tool_failure_count=None,
                 tool_unfinished_count=None, tool_failure_rate=None, tool_stats_complete=False,
                 tool_calls_by_name=None)
    if directory is None:
        return empty
    try:
        lines = (directory / "agent/eval/events.jsonl").read_text(encoding="utf-8").splitlines()
    except OSError:
        return empty
    starts, results, seen = {}, {}, set()
    complete = True
    finished = False
    for line in lines:
        try:
            event = json.loads(line)
            sequence = event.get("sequence")
            if sequence is not None:
                if sequence in seen:
                    continue
                seen.add(sequence)
            kind, payload = event.get("type"), event.get("payload", {})
            if kind == "RUN_FINISHED":
                finished = True
            if kind not in ("TOOL_STARTED", "TOOL_RESULT"):
                continue
            call_id, name = payload["call_id"], payload["name"]
            if kind == "TOOL_STARTED":
                starts[call_id] = name
            else:
                success = payload["result"]["success"]
                if not isinstance(success, bool):
                    raise ValueError("Invalid tool outcome")
                results[call_id] = (name, success)
        except (ValueError, KeyError, TypeError, AttributeError):
            complete = False
    by_name = {}
    for call_id in starts.keys() | results.keys():
        name = starts.get(call_id, results.get(call_id, (None,))[0])
        counts = by_name.setdefault(name, dict(calls=0, succeeded=0, failed=0, unfinished=0))
        counts["calls"] += 1
        outcome = results.get(call_id)
        counts["unfinished" if outcome is None else "succeeded" if outcome[1] else "failed"] += 1
        if call_id not in starts:
            complete = False
    calls = sum(c["calls"] for c in by_name.values())
    succeeded = sum(c["succeeded"] for c in by_name.values())
    failed = sum(c["failed"] for c in by_name.values())
    unfinished = calls - succeeded - failed
    return dict(tool_call_count=calls, tool_success_count=succeeded, tool_failure_count=failed,
                tool_unfinished_count=unfinished,
                tool_failure_rate=failed / (succeeded + failed) if succeeded + failed else None,
                tool_stats_complete=complete and finished and unfinished == 0,
                tool_calls_by_name=dict(sorted(by_name.items())))


def recover_attempts(directory):
    """Best-effort partial accounting when an outer timeout kills Java before final metrics."""
    attempts = []
    try:
        for line in (directory / "agent/eval/attempts.jsonl").read_text(encoding="utf-8").splitlines():
            try:
                attempts.append(json.loads(line))
            except ValueError:
                pass  # A last interrupted journal write must not hide earlier requests.
    except OSError:
        return {}
    usage = [attempt["usage"] for attempt in attempts if attempt.get("usage")]
    result = {"request_count": len(attempts), "retry_count": sum(a.get("attempt", 1) > 1 for a in attempts),
              "usage_complete": False, "model_request_ms": sum(a.get("elapsed_millis", 0) for a in attempts)}
    for key in ("input_tokens", "output_tokens", "total_tokens", "cached_tokens"):
        known = [u[key] for u in usage if u.get(key) is not None]
        result[key] = sum(known) if known else None
    return result


def summarize(output, tasks, provenance, infrastructure_error=None):
    rows = []
    results = {}
    for path in (output / "jobs").glob("*/*/result.json"):
        trial = read_json(path)
        if trial.get("task_name") in tasks:
            results[trial["task_name"]] = (path.parent, trial)
    for task in tasks:
        directory, trial = results.get(task, (None, {}))
        metrics = read_json(directory / "agent/eval/metrics.json") if directory else {}
        if directory and not metrics:
            metrics = recover_attempts(directory)
        verification = read_json(directory / "verifier/verification.json") if directory else {}
        rewards = (trial.get("verifier_result") or {}).get("rewards") or {}
        reward = rewards.get("reward", verification.get("reward", 0))
        exception = trial.get("exception_info") or {}
        row = {
            "task": task, "model": provenance.get("model"),
            "agent_status": metrics.get("agent_status", "NOT_RUN" if not trial else "UNKNOWN"),
            "functional_passed": verification.get("functional_passed", False),
            "regression_passed": verification.get("regression_passed", False),
            "reward": reward, "passed": reward == 1,
            "input_tokens": metrics.get("input_tokens"), "output_tokens": metrics.get("output_tokens"),
            "total_tokens": metrics.get("total_tokens"), "cached_tokens": metrics.get("cached_tokens"),
            "usage_complete": metrics.get("usage_complete", False),
            "cache_usage_complete": metrics.get("cache_usage_complete", False),
            "cache_hit_rate": cache_hit_rate(metrics),
            "request_count": metrics.get("request_count"), "retry_count": metrics.get("retry_count"),
            "model_request_ms": metrics.get("model_request_ms"),
            "agent_elapsed_ms": metrics.get("agent_elapsed_ms", phase_ms(trial.get("agent_execution"))),
            "verifier_elapsed_ms": verification.get("verifier_elapsed_ms", phase_ms(trial.get("verifier"))),
            "environment_setup_ms": phase_ms(trial.get("environment_setup")),
            "end_to_end_ms": phase_ms(trial),
            "error": metrics.get("error")
                     or (metrics.get("final_response") if metrics.get("agent_status") not in (None, "COMPLETED") else None)
                     or verification.get("error") or exception.get("exception_message")
                     or (infrastructure_error if not trial else None),
            "trial_directory": str(directory) if directory else None,
        }
        row.update(tool_statistics(directory))
        rows.append(row)
    passed = sum(row["passed"] for row in rows)
    report = {"schema_version": 1, "provenance": provenance,
              "planned_tasks": len(tasks), "passed_tasks": passed,
              "success_rate": passed / len(tasks) if tasks else 0,
              "infrastructure_error": infrastructure_error, "tasks": rows}
    report["cache_hit_rate"] = (
        sum(row["cached_tokens"] for row in rows) / sum(row["input_tokens"] for row in rows)
        if rows and all(row["cache_hit_rate"] is not None for row in rows) else None)
    (output / "summary.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    with (output / "summary.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        if rows:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows({**row, "tool_calls_by_name": json.dumps(row["tool_calls_by_name"], ensure_ascii=False)}
                             for row in rows)
    return report


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Rebuild reports from existing logs; no Docker or model calls.")
    parser.add_argument("output", type=Path, help="Existing evaluation result directory")
    args = parser.parse_args()
    previous = read_json(args.output / "summary.json")
    if not previous.get("tasks"):
        parser.error("An existing summary.json with planned tasks is required")
    summarize(args.output, [row["task"] for row in previous["tasks"]],
              previous.get("provenance", {}), previous.get("infrastructure_error"))
