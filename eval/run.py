"""Container-side local job launcher, pinned to Harbor 0.24.0."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import subprocess
import time

from report import summarize
from model_config import apply_model_config


async def run_job(args, tasks):
    from harbor.job import Job
    from harbor.models.job.config import JobConfig
    from harbor.models.trial.config import AgentConfig, TaskConfig

    if args.mode == "agent":
        agent = AgentConfig(import_path="adapter:CodingAgent", model_name=os.environ["LLM_MODEL"])
    elif args.mode == "oracle":
        agent = AgentConfig(name="oracle")
    else:
        agent = AgentConfig(import_path="baseline:NoopAgent")
    config = JobConfig(job_name="evaluation", jobs_dir=args.output_dir / "jobs", n_attempts=1,
                       n_concurrent_trials=1, agents=[agent],
                       tasks=[TaskConfig(path=Path(__file__).parent / "tasks" / name) for name in tasks])
    job = await Job.create(config)

    async def record_trial(event):
        # Persist before environment creation so the host can clean up this run even after interruption.
        with (args.output_dir / "trial-manifest.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"trial_name": event.trial_name, "task_name": event.task_name}) + "\n")

    job.on_trial_started(record_trial)
    await job.run()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--task", action="append", choices=["text-stats", "csv-parser", "todo-filter"])
    parser.add_argument("--mode", choices=["agent", "oracle", "initial"], default="agent")
    parser.add_argument("--allow-model-calls", action="store_true",
                        help="Explicit one-invocation consent to consume model tokens; never set in automation")
    args = parser.parse_args()
    if args.mode == "agent" and not args.allow_model_calls:
        parser.error("Real-model evaluation requires an explicit user request and --allow-model-calls")
    # Do not trust an inherited marker; only this invocation's explicit CLI flag can grant consent.
    os.environ.pop("CODING_AGENT_EVAL_MODEL_CALLS", None)
    if args.mode == "agent" and args.allow_model_calls:
        os.environ["CODING_AGENT_EVAL_MODEL_CALLS"] = "explicit"
    tasks = args.task or ["text-stats", "csv-parser", "todo-filter"]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    if (args.output_dir / "jobs").exists():
        parser.error("Use a fresh output directory for every evaluation")
    import yaml
    provenance_file = args.output_dir / "provenance.json"
    provenance = json.loads(provenance_file.read_text(encoding="utf-8-sig")) if provenance_file.exists() else {}
    started = time.monotonic()
    error = None
    try:
        try:
            defaults = yaml.safe_load((Path(__file__).parent / "config.yaml").read_text(encoding="utf-8"))
            local_path = os.environ.get("EVAL_MODEL_CONFIG")
            local = yaml.safe_load(Path(local_path).read_text(encoding="utf-8-sig")) if local_path else {}
        except (OSError, yaml.YAMLError):
            raise ValueError("Cannot read or parse model configuration") from None
        apply_model_config(defaults, local or {}, os.environ)
        provenance.update({"harbor_version": "0.24.0", "mode": args.mode, "model": os.environ.get("LLM_MODEL"),
                           "provider": os.environ.get("LLM_PROVIDER"), "max_iterations": os.environ.get("AGENT_MAX_ITERATIONS", "30")})
        if args.mode == "agent" and not os.environ.get("LLM_API_KEY", "").strip():
            raise ValueError("LLM_API_KEY is required; use --mode oracle or initial for offline validation")
        # Critical for Docker-outside-of-Docker: this exact log path must exist in the daemon namespace.
        probe = args.output_dir / "mount-probe.txt"
        probe.write_text("harbor-eval-probe", encoding="utf-8")
        check = subprocess.run(["docker", "run", "--rm", "--network", "none", "--mount",
                                f"type=bind,source={args.output_dir},target=/probe,readonly",
                                "coding-agent-eval-base:local", "cat", "/probe/mount-probe.txt"],
                               capture_output=True, text=True, check=True, timeout=30)
        if check.stdout.strip() != "harbor-eval-probe":
            raise RuntimeError("Docker daemon and orchestrator do not share the same output path")
        probe.unlink()
        asyncio.run(run_job(args, tasks))
    except (Exception, KeyboardInterrupt) as exc:
        error = type(exc).__name__ + ": " + str(exc)
        # Never persist a provider credential even if a downstream exception includes it.
        if os.environ.get("LLM_API_KEY"):
            error = error.replace(os.environ["LLM_API_KEY"], "[redacted]")
        print(error, flush=True)
    finally:
        provenance["job_elapsed_ms"] = round((time.monotonic() - started) * 1000)
        report = summarize(args.output_dir, tasks, provenance, error)
    print(f"Passed {report['passed_tasks']}/{report['planned_tasks']}. Reports: {args.output_dir}", flush=True)
    return 2 if error else 0 if report["passed_tasks"] == len(tasks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
