"""Local public checks; no network or model required."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent
junit = Path(os.environ.get("JUNIT_JAR", "/opt/junit/console.jar")).resolve()
with tempfile.TemporaryDirectory(prefix="public-check-", dir=root) as temporary:
    classes = Path(temporary)
    sources = sorted((root / "src").rglob("*.java")) + sorted((root / "public-tests").rglob("*.java"))
    subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-cp", str(junit),
                    "-d", str(classes)] + [str(p) for p in sources], check=True)
    raise SystemExit(subprocess.run(["java", "-Dfile.encoding=UTF-8", "-Deval.source.dir=" + str(root / "src"),
                                    "-jar", str(junit), "execute", "--disable-banner", "--fail-if-no-tests",
                                    "--class-path", str(classes), "--scan-class-path"]).returncode)
