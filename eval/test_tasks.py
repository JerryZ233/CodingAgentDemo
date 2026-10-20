"""Task selection and fixture contracts; never starts Harbor or a model."""
import contextlib
import io
from pathlib import Path
import unittest

from run import TASKS, parse_args


class TaskSelectionTest(unittest.TestCase):
    def test_default_and_single_task_and_duplicate_do_not_add_trials(self):
        args = ["--output-dir", "unused", "--mode", "oracle"]
        self.assertEqual(list(TASKS), parse_args(args).task)
        for task in TASKS:
            self.assertEqual([task], parse_args(args + ["--task", task]).task)
            self.assertEqual([task], parse_args(args + ["--task", task, "--task", task]).task)
        roots = Path(__file__).parent / "tasks"
        self.assertEqual(set(TASKS), {p.name for p in roots.iterdir() if (p / "task.toml").is_file()})

    def test_removed_names_and_missing_model_consent_rejected(self):
        for task in ("text-stats", "todo-filter"):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                parse_args(["--output-dir", "unused", "--mode", "initial", "--task", task])
            self.assertEqual(2, error.exception.code)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            parse_args(["--output-dir", "unused"])

    def test_fixtures_fit_existing_verifier_limits_and_references_are_hidden(self):
        root = Path(__file__).parent / "tasks"
        for task in TASKS:
            with self.subTest(task=task):
                workspace = root / task / "environment/workspace"
                for source in (workspace / "src", root / task / "solution/reference/src"):
                    files = list(source.rglob("*"))
                    self.assertLessEqual(len(files), 200)
                    self.assertLessEqual(sum(p.stat().st_size for p in files if p.suffix == ".java"), 2_000_000)
                self.assertFalse((workspace / "solution").exists())
                self.assertFalse((workspace / "tests").exists())
        large = root / "large-file-edit/environment/workspace/src/PricingRules.java"
        self.assertGreater(large.stat().st_size, 8000)
        self.assertLess(large.stat().st_size, 1024 * 1024)
        self.assertNotIn(b"\r", large.read_bytes())


if __name__ == "__main__":
    unittest.main()
