"""Build retry/offline contracts using a fake Gradle executable; no model calls."""
import os
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


POWERSHELL = shutil.which("pwsh") or shutil.which("powershell")


@unittest.skipUnless(POWERSHELL, "Requires PowerShell")
class CacheSeedTest(unittest.TestCase):
    def prepare(self, no_host_cache=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "gradle home"
            allowed = {
                "caches/modules-2/files-2.1/example/library/1.0/hash/library.jar",
                "caches/modules-2/metadata-2.106/descriptors/entry.bin",
                "wrapper/dists/gradle-8.10.2-bin/complete/gradle-8.10.2/lib/example.jar",
                "wrapper/dists/gradle-8.10.2-bin/complete/gradle-8.10.2-bin.zip.ok",
            }
            forbidden = {
                "gradle.properties", "init.d/credentials.gradle", "daemon/private.log",
                "caches/modules-2/modules-2.lock", "caches/modules-2/gc.properties",
                "caches/modules-2/metadata-2.106/metadata.lock",
                "wrapper/dists/gradle-8.10.2-bin/complete/gradle-8.10.2-bin.zip.lck",
                "wrapper/dists/gradle-8.10.2-bin/incomplete/gradle-8.10.2-bin.zip.part",
            }
            for name in allowed | forbidden:
                file = home / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("fixture", encoding="utf-8")
            manifest = root / "manifest.json"
            environment = dict(os.environ, AGENT_TEST_HOME=str(home), AGENT_TEST_MANIFEST=str(manifest),
                               AGENT_TEST_NO_HOST_CACHE=str(no_host_cache).lower(),
                               AGENT_TEST_HELPER=str(Path(__file__).parent / "build-agent.ps1"))
            script = r"""
$ErrorActionPreference = 'Stop'
function docker {
    if ($args[0] -ne 'build') { throw 'Unexpected Docker command' }
    $contextIndex = [Array]::IndexOf($args, '--build-context')
    $seed = $args[$contextIndex + 1].Substring('gradle-seed='.Length)
    $files = @(Get-ChildItem -LiteralPath $seed -Recurse -File | ForEach-Object {
        $_.FullName.Substring($seed.Length).TrimStart([char[]]'\/').Replace('\', '/')
    })
    @{ seed = $seed; files = $files } | ConvertTo-Json | Set-Content -LiteralPath $env:AGENT_TEST_MANIFEST -Encoding UTF8
    $global:LASTEXITCODE = 0
}
& $env:AGENT_TEST_HELPER -GradleUserHome $env:AGENT_TEST_HOME -NoHostCache:($env:AGENT_TEST_NO_HOST_CACHE -eq 'true')
"""
            result = subprocess.run([POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", script],
                                    env=environment, capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, result.stderr)
            actual = json.loads(manifest.read_text(encoding="utf-8-sig"))
            self.assertFalse(Path(actual["seed"]).exists(), "Temporary cache copy must be cleaned up")
            return set(actual["files"]), allowed

    def test_only_portable_cache_files_are_copied_and_staging_is_cleaned(self):
        actual, allowed = self.prepare()
        self.assertEqual(allowed, actual)

    def test_no_host_cache_uses_an_empty_context(self):
        actual, _ = self.prepare(no_host_cache=True)
        self.assertEqual(set(), actual)


@unittest.skipUnless(shutil.which("sh"), "Requires a POSIX shell (also runs in the evaluation image)")
class AgentBuildTest(unittest.TestCase):
    def build(self, failures, offline=False, cached_wrapper=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed = root / "seed"
            seed.mkdir()
            (seed / "cached-artifact").write_text("cached", encoding="utf-8")
            wrapper = root / "gradle/wrapper"
            wrapper.mkdir(parents=True)
            (wrapper / "gradle-wrapper.properties").write_text(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n")
            if cached_wrapper:
                distribution = seed / "wrapper/dists/gradle-8.10.2-bin/cached"
                distribution.mkdir(parents=True)
                (distribution / "gradle-8.10.2-bin.zip.ok").touch()
            gradlew = root / "gradlew"
            gradlew.write_text("""#!/bin/sh
echo "$*" >> attempts
test -f "$GRADLE_USER_HOME/cached-artifact" || exit 99
count=$(wc -l < attempts)
test "$count" -gt "$FAILURES"
""", encoding="utf-8")
            environment = dict(os.environ, GRADLE_USER_HOME=str(root / "cache"),
                               GRADLE_SEED_DIR=str(seed), FAILURES=str(failures),
                               GRADLE_BUILD_OFFLINE=str(offline).lower())
            result = subprocess.run([shutil.which("sh"), str(Path(__file__).parent / "docker/build-agent.sh")],
                                    cwd=root, env=environment, capture_output=True, text=True, timeout=30)
            attempts = root / "attempts"
            return result, attempts.read_text().splitlines() if attempts.exists() else []

    def test_complete_cache_never_tries_online(self):
        result, attempts = self.build(0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(attempts))
        self.assertIn("--offline", attempts[0])

    def test_explicit_offline_failure_never_falls_back(self):
        result, attempts = self.build(10, offline=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(attempts))

    def test_missing_wrapper_in_offline_mode_never_starts_download(self):
        result, attempts = self.build(0, offline=True, cached_wrapper=False)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], attempts)
        self.assertIn("cached Gradle wrapper", result.stderr)

    def test_transient_online_failure_retries_only_build(self):
        result, attempts = self.build(2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, len(attempts))
        self.assertTrue(all("--offline" not in line for line in attempts[1:]))

    def test_persistent_failure_is_bounded(self):
        result, attempts = self.build(10)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(4, len(attempts))  # one offline attempt and three online attempts


if __name__ == "__main__":
    unittest.main()
