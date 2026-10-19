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
        rows.append(row)
    passed = sum(row["passed"] for row in rows)
    report = {"schema_version": 1, "provenance": provenance,
              "planned_tasks": len(tasks), "passed_tasks": passed,
              "success_rate": passed / len(tasks) if tasks else 0,
              "infrastructure_error": infrastructure_error, "tasks": rows}
    (output / "summary.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    with (output / "summary.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        if rows:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    return report
