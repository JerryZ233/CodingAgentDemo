"""Deterministic Docker smoke test: no API credential, network, or host filesystem mount."""
import json
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).parent
    payload = {"prepare": (root / "prepare_workspace.py").read_text(encoding="utf-8"),
               "java": (root / "WorkspaceProbe.java").read_text(encoding="utf-8")}
    bootstrap = """import sys,json,pathlib,subprocess
data=json.load(sys.stdin)
pathlib.Path('/tmp/prepare-workspace.py').write_text(data['prepare'])
pathlib.Path('/tmp/WorkspaceProbe.java').write_text(data['java'])
subprocess.run(['python3','/tmp/prepare-workspace.py','/workspace'],check=True)
raise SystemExit(subprocess.run(['java','-cp','/opt/agent/lib/*','/tmp/WorkspaceProbe.java']).returncode)
"""
    result = subprocess.run(["docker", "run", "--rm", "-i", "--network", "none",
                             "coding-agent-eval-text-stats:local", "python3", "-c", bootstrap],
                            input=json.dumps(payload), capture_output=True, text=True, timeout=60)
    print(result.stdout, end="")
    if result.returncode or "WRITE_AND_BINDING_OK" not in result.stdout:
        print(result.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
