"""Trusted verifier: compile source only, never execute candidate build scripts or tests."""
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET


def invoke(args, log, timeout=35):
    try:
        with log.open("w", encoding="utf-8") as stream:
            return subprocess.run(args, stdout=stream, stderr=subprocess.STDOUT,
                                  timeout=timeout, check=False).returncode
    except subprocess.TimeoutExpired:
        with log.open("a", encoding="utf-8") as stream:
            stream.write("\nVERIFIER COMMAND TIMED OUT\n")
        return 124


def verify(source=Path("/workspace/src"), tests=Path("/tests"), logs=Path("/logs/verifier"),
           junit=Path("/opt/junit/console.jar")):
    started = time.monotonic()
    logs.mkdir(parents=True, exist_ok=True)
    result = {"functional_passed": False, "regression_passed": False, "reward": 0}
    try:
        # Reject links and oversized trees; compile into a fresh trusted directory.
        files = sorted(source.rglob("*"))
        if source.is_symlink() or any(p.is_symlink() for p in files):
            raise ValueError("Source symlinks are not accepted")
        sources = [p for p in files if p.is_file() and p.suffix == ".java"]
        if not sources or len(files) > 200 or sum(p.stat().st_size for p in sources) > 2_000_000:
            raise ValueError("Missing or oversized source submission")
        classes = logs / "classes"
        classes.mkdir(exist_ok=False)
        code = invoke(["javac", "-proc:none", "-encoding", "UTF-8", "-d", str(classes)]
                      + [str(p) for p in sources], logs / "compile.log")
        result["compile_exit_code"] = code
        if code == 0:
            code = invoke(["javac", "-proc:none", "-encoding", "UTF-8", "-cp", os.pathsep.join([str(classes), str(junit)]),
                           "-d", str(classes)] + [str(p) for p in sorted(tests.glob("*.java"))],
                          logs / "compile-tests.log")
            result["test_compile_exit_code"] = code
        for group in ("functional", "regression"):
            if code != 0:
                result[group + "_exit_code"] = None
                continue
            reports = logs / group
            status = invoke(["java", "-Dfile.encoding=UTF-8", "-Deval.source.dir=" + str(source.resolve()),
                             "-jar", str(junit), "execute", "--disable-banner", "--fail-if-no-tests",
                             "--class-path", str(classes), "--select-class",
                             "FunctionalTest" if group == "functional" else "RegressionTest",
                             "--reports-dir", str(reports)], logs / (group + ".log"), timeout=25)
            result[group + "_exit_code"] = status
            # No green verdict from an early System.exit(0): require actual nonempty JUnit reports.
            suites = []
            for report in reports.glob("TEST-*.xml"):
                suites.append(ET.parse(report).getroot())
            count = sum(int(s.get("tests", "0")) for s in suites)
            failures = sum(int(s.get("failures", "0")) + int(s.get("errors", "0"))
                           + int(s.get("skipped", "0")) for s in suites)
            result[group + "_passed"] = status == 0 and count > 0 and failures == 0
        result["reward"] = int(result["functional_passed"] and result["regression_passed"])
    except Exception as exc:
        result["error"] = type(exc).__name__ + ": " + str(exc)
    finally:
        result["verifier_elapsed_ms"] = round((time.monotonic() - started) * 1000)
        (logs / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        (logs / "reward.txt").write_text(str(result["reward"]), encoding="utf-8")
    return result


if __name__ == "__main__":
    verify()
