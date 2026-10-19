import json
from pathlib import Path
import tempfile
import unittest
from report import summarize, phase_ms


class ReportTest(unittest.TestCase):
    def test_harbor_timestamps_and_missing_phase_times(self):
        self.assertEqual(1500, phase_ms({"started_at": "2026-10-20T00:00:00Z", "finished_at": "2026-10-20T00:00:01.500Z"}))
        self.assertIsNone(phase_ms({"started_at": "interrupted", "finished_at": "invalid"}))

    def test_failed_or_missing_trials_remain_in_denominator(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            trial = output / "jobs/evaluation/text-stats__trial"
            (trial / "agent/eval").mkdir(parents=True)
            (trial / "verifier").mkdir()
            (trial / "result.json").write_text(json.dumps({"task_name": "text-stats",
                "verifier_result": {"rewards": {"reward": 1}}}), encoding="utf-8")
            (trial / "agent/eval/metrics.json").write_text(json.dumps({"agent_status": "COMPLETED",
                "input_tokens": 12, "output_tokens": 3, "total_tokens": 15, "usage_complete": True}), encoding="utf-8")
            (trial / "verifier/verification.json").write_text(json.dumps({"functional_passed": True,
                "regression_passed": True, "reward": 1}), encoding="utf-8")
            report = summarize(output, ["text-stats", "csv-parser"], {"model": "test"}, "engine failed")
            self.assertEqual(0.5, report["success_rate"])
            self.assertEqual(15, report["tasks"][0]["total_tokens"])
            self.assertEqual("NOT_RUN", report["tasks"][1]["agent_status"])
            self.assertIsNone(report["tasks"][1]["total_tokens"])
            self.assertFalse(report["tasks"][1]["usage_complete"])
            self.assertTrue((output / "summary.csv").exists())

    def test_normal_exit_does_not_imply_pass_and_malformed_logs_are_tolerated(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            trial = output / "jobs/evaluation/task__trial"
            (trial / "agent/eval").mkdir(parents=True)
            (trial / "result.json").write_text('{"task_name":"task"}', encoding="utf-8")
            (trial / "agent/eval/metrics.json").write_text("incomplete", encoding="utf-8")
            report = summarize(output, ["task"], {})
            self.assertEqual(0, report["success_rate"])
            self.assertFalse(report["tasks"][0]["passed"])
            self.assertIsNone(report["tasks"][0]["total_tokens"])

    def test_hard_timeout_recovers_partial_journal_without_claiming_complete_usage(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            trial = output / "jobs/evaluation/task__trial"
            (trial / "agent/eval").mkdir(parents=True)
            (trial / "result.json").write_text('{"task_name":"task"}', encoding="utf-8")
            (trial / "agent/eval/attempts.jsonl").write_text(
                '{"attempt":1,"elapsed_millis":10,"usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9}}\n{"attempt":', encoding="utf-8")
            report = summarize(output, ["task"], {})
            self.assertEqual(9, report["tasks"][0]["total_tokens"])
            self.assertFalse(report["tasks"][0]["usage_complete"])

    def test_model_failure_reason_survives_independent_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            trial = output / "jobs/evaluation/task__trial"
            (trial / "agent/eval").mkdir(parents=True)
            (trial / "result.json").write_text('{"task_name":"task"}', encoding="utf-8")
            (trial / "agent/eval/metrics.json").write_text(json.dumps({"agent_status": "LLM_ERROR",
                "final_response": 'source: TRANSPORT; exception: SSLHandshakeException'}), encoding="utf-8")
            report = summarize(output, ["task"], {})
            self.assertIn("SSLHandshakeException", report["tasks"][0]["error"])


if __name__ == "__main__":
    unittest.main()
