"""Cross-platform task contract checks; no model or Docker required. Requires JDK 17 + JUnit console."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import shutil
from verifier import verify


def check(junit, output):
    root = Path(__file__).parent / "tasks"
    results = []
    for task in sorted(root.iterdir()):
        if not (task / "task.toml").exists():
            continue
        solution = (task / "solution/solve.sh").read_text(encoding="utf-8")
        reference = dict(re.findall(r"cat > /workspace/src/([^\n]+) <<'JAVA'\n(.*?)\nJAVA\n", solution, re.S))
        if not reference:
            raise ValueError("No reference source found for " + task.name)
        for mode in ("initial", "oracle", "corrupt"):
            directory = output / task.name / mode
            source = directory / "src"
            source.mkdir(parents=True, exist_ok=False)
            if mode == "initial":
                for file in (task / "environment/workspace/src").glob("*.java"):
                    shutil.copyfile(file, source / file.name)
            else:
                for filename, code in reference.items():
                    if mode == "corrupt":
                        if filename == "TextStats.java":
                            code = code.replace("return text.length();", "return -1;")
                        elif filename == "CsvParser.java":
                            code = code.replace("if(csv.isEmpty())return rows;", 'if(csv.isEmpty())return List.of(List.of("broken"));')
                        elif filename == "TodoStore.java":
                            code = code.replace("public List<Item> list(){return List.copyOf(items);}", "public List<Item> list(){return List.of();}")
                    (source / filename).write_text(code, encoding="utf-8")
            result = verify(source.resolve(), (task / "tests").resolve(), (directory / "logs").resolve(), junit.resolve())
            expected = (False, True) if mode == "initial" else (True, True) if mode == "oracle" else (True, False)
            actual = (result["functional_passed"], result["regression_passed"])
            passed = actual == expected
            results.append({"task": task.name, "mode": mode, "passed": passed, "result": result})
            print(f"{task.name}/{mode}: {'PASS' if passed else 'FAIL'} functional={actual[0]} regression={actual[1]}", flush=True)
    (output / "checks.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return all(row["passed"] for row in results)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--junit", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/eval-task-check") / datetime.now().strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    raise SystemExit(0 if check(args.junit, args.output) else 1)
