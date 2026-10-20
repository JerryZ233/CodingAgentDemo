"""Cross-platform task contract checks; no model or Docker required. Requires JDK 17 + JUnit console."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from verifier import verify


def check(junit, output):
    root = Path(__file__).parent / "tasks"
    results = []
    for task in sorted(root.iterdir()):
        if not (task / "task.toml").exists():
            continue
        initial = task / "environment/workspace/src"
        reference = task / "solution/reference/src"
        if not any(reference.rglob("*.java")):
            raise ValueError("No reference source found for " + task.name)
        mutation = json.loads((task / "solution/corrupt.json").read_text(encoding="utf-8"))
        for mode in ("initial", "oracle", "corrupt"):
            directory = output / task.name / mode
            source = directory / "src"
            shutil.copytree(initial, source)
            if mode != "initial":
                shutil.copytree(reference, source, dirs_exist_ok=True)
            if mode == "corrupt":
                target = (source / mutation["path"]).resolve()
                target.relative_to(source.resolve())
                original = target.read_bytes()
                old, new = mutation["old"].encode("utf-8"), mutation["new"].encode("utf-8")
                if not old or original.count(old) != 1 or old == new:
                    raise ValueError("Corruption must change exactly one anchor in " + task.name)
                target.write_bytes(original.replace(old, new, 1))
            result = verify(source.resolve(), (task / "tests").resolve(), (directory / "logs").resolve(), junit.resolve())
            expected = (False, True) if mode == "initial" else (True, True) if mode == "oracle" else (True, False)
            actual = (result["functional_passed"], result["regression_passed"])
            passed = actual == expected
            public_code = None
            if mode in ("initial", "oracle") and (task / "environment/workspace/test.py").exists():
                public = directory / "public workspace 中文"
                shutil.copytree(task / "environment/workspace", public)
                shutil.copytree(source, public / "src", dirs_exist_ok=True)
                with (directory / "public.log").open("w", encoding="utf-8") as log:
                    try:
                        public_code = subprocess.run([sys.executable, str(public.resolve() / "test.py")],
                                                     stdout=log, stderr=subprocess.STDOUT, timeout=90,
                                                     env={**os.environ, "JUNIT_JAR": str(junit.resolve())}).returncode
                    except subprocess.TimeoutExpired:
                        public_code = 124
                passed = passed and public_code == (0 if mode == "oracle" else 1)
            results.append({"task": task.name, "mode": mode, "passed": passed, "result": result,
                            "public_exit_code": public_code})
            print(f"{task.name}/{mode}: {'PASS' if passed else 'FAIL'} functional={actual[0]} regression={actual[1]} public_exit={public_code}", flush=True)
    (output / "checks.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return all(row["passed"] for row in results)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--junit", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/eval-task-check") / datetime.now().strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    raise SystemExit(0 if check(args.junit, args.output) else 1)
