import json
from pathlib import Path
import tempfile
import unittest
from report import summarize, phase_ms, cache_hit_rate, tool_statistics


class ReportTest(unittest.TestCase):
    def test_cache_requires_complete_usage_and_nonzero_denominator(self):
        metrics = dict(input_tokens=100, cached_tokens=80, usage_complete=True, cache_usage_complete=True)
        self.assertEqual(0.8, cache_hit_rate(metrics))
        self.assertEqual(0, cache_hit_rate({**metrics, "cached_tokens": 0}))
        for changed in ({"cache_usage_complete": False}, {"usage_complete": False},
                        {"input_tokens": 0}, {"cached_tokens": None}, {"cached_tokens": 101}):
            self.assertIsNone(cache_hit_rate({**metrics, **changed}))

    def test_tool_outcomes_duplicates_and_interrupted_journal(self):
        with tempfile.TemporaryDirectory() as directory:
            trial = Path(directory)
            (trial / "agent/eval").mkdir(parents=True)
            events = [
                dict(sequence=1, type="TOOL_STARTED", payload=dict(call_id="a", name="read_file")),
                dict(sequence=2, type="TOOL_RESULT", payload=dict(call_id="a", name="read_file", result=dict(success=True))),
                dict(sequence=3, type="TOOL_STARTED", payload=dict(call_id="b", name="shell")),
                dict(sequence=4, type="TOOL_RESULT", payload=dict(call_id="b", name="shell", result=dict(success=False))),
                dict(sequence=5, type="TOOL_STARTED", payload=dict(call_id="c", name="shell")),
            ]
            path = trial / "agent/eval/events.jsonl"
            path.write_text("\n".join(json.dumps(e) for e in events + [events[0]]) + '\n{"type":', encoding="utf-8")
            stats = tool_statistics(trial)
            self.assertEqual(3, stats["tool_call_count"])
            self.assertEqual(1, stats["tool_success_count"])
            self.assertEqual(1, stats["tool_failure_count"])
            self.assertEqual(1, stats["tool_unfinished_count"])
            self.assertEqual(0.5, stats["tool_failure_rate"])
            self.assertFalse(stats["tool_stats_complete"])
            self.assertEqual(2, stats["tool_calls_by_name"]["shell"]["calls"])
            self.assertIsNone(tool_statistics(None)["tool_call_count"])

    def test_report_cache_is_weighted_and_csv_contains_tool_json(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for name, inputs, cached in (("a", 100, 90), ("b", 300, 150)):
                trial = output / "jobs/evaluation" / name
                (trial / "agent/eval").mkdir(parents=True)
                (trial / "result.json").write_text(json.dumps(dict(task_name=name)), encoding="utf-8")
                (trial / "agent/eval/metrics.json").write_text(json.dumps(dict(
                    input_tokens=inputs, cached_tokens=cached, usage_complete=True,
                    cache_usage_complete=True)), encoding="utf-8")
                (trial / "agent/eval/events.jsonl").write_text(
                    '{"type":"RUN_FINISHED","payload":{}}\n', encoding="utf-8")
            report = summarize(output, ["a", "b"], {})
            self.assertEqual(0.6, report["cache_hit_rate"])
            self.assertEqual(0, report["tasks"][0]["tool_call_count"])
            self.assertTrue(report["tasks"][0]["tool_stats_complete"])
            self.assertIsNone(report["tasks"][0]["tool_failure_rate"])
            report = summarize(output, ["a", "b", "missing"], {})
            self.assertIsNone(report["cache_hit_rate"])

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
