"""Behavior checks for the real config, metrics, and report boundaries."""
import importlib
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class InfrastructureTests(unittest.TestCase):
    def module(self, name):
        try:
            return importlib.import_module(name)
        except ImportError:
            self.fail(f"Missing implementation: {name}")

    def test_connection_secret_roundtrip_and_public_identity_never_contains_secret(self):
        m = self.module("dit_config")
        encrypted = m.encrypt_credential("TTBP", "test-only-secret", "test-key")
        self.assertNotIn("test-only-secret", encrypted)
        self.assertEqual(m.decrypt_credential(encrypted, "test-key"), ("TTBP", "test-only-secret"))
        profile = m.Profile(code="SKY_UAT", provider="ORACLE", host="127.0.0.1", port=1521,
                            username="TTBP", password="test-only-secret", service="UAT", sid=None, database=None)
        self.assertNotIn("test-only-secret", repr(profile))
        self.assertNotIn("test-only-secret", json.dumps(profile.public()))
        self.assertEqual(profile.oracle_dsn(), "127.0.0.1:1521/UAT")

    def test_ambiguous_oracle_identity_is_rejected_instead_of_using_wrong_service(self):
        m = self.module("dit_config")
        profile = m.Profile(code="SKY_UAT", provider="ORACLE", host="127.0.0.1", port=1521,
                            username="TTBP", password="fake", service="UAT", sid="PROD", database=None)
        with self.assertRaises(ValueError):
            profile.oracle_dsn()

    def test_env_parser_is_literal_and_process_values_win(self):
        m = self.module("dit_config")
        with tempfile.TemporaryDirectory() as temp:
            f = Path(temp) / ".env"
            f.write_text('A="a=b"\nB=$(never-execute)\n# comment\n', encoding="utf-8")
            self.assertEqual(m.read_environment(f, {"A": "override"}), {"A":"override", "B":"$(never-execute)"})

    def test_cpu_normalization_and_counter_reset_do_not_invent_zero(self):
        m = self.module("dit_metrics")
        self.assertEqual(m.normalize_process_cpu(240, 8), 30)
        self.assertIsNone(m.normalize_process_cpu(None, 8))
        self.assertIsNone(m.normalize_process_cpu(10, 0))
        self.assertEqual(m.counter_cpu({"BUSY_TIME":100,"IDLE_TIME":100}, {"BUSY_TIME":140,"IDLE_TIME":160}),40)
        self.assertIsNone(m.counter_cpu({"BUSY_TIME":100,"IDLE_TIME":100}, {"BUSY_TIME":1,"IDLE_TIME":1}))

    def test_real_sampler_without_pid_has_host_memory_but_no_fake_process_values(self):
        import time
        m = self.module("dit_metrics")
        sampler = m.HostSampler([])
        time.sleep(.03)
        sample = sampler.sample()
        self.assertGreater(sample["hostMemoryUsedMiB"], 0)
        self.assertGreater(sample["hostMemoryAvailableMiB"], 0)
        self.assertIsNone(sample["processCpuPercent"])
        self.assertIsNone(sample["processWorkingSetMiB"])
        self.assertGreater(sampler.inventory()["logicalCores"], 0)
        self.assertTrue(0 <= sample["hostCpuPercent"] <= 100)

    def test_report_atomic_write_preserves_empty_runs_and_escapes_script_closing(self):
        m = self.module("dit_report")
        with tempfile.TemporaryDirectory() as temp:
            report = m.blank_report()
            report["report"]["notes"] = "</script><script>fake()</script>"
            json_path, html_path = m.write_report(Path(temp), report)
            self.assertEqual(json.loads(json_path.read_text(encoding="utf-8"))["runs"], [])
            content = html_path.read_text(encoding="utf-8")
            import re
            embedded = re.search(r'<script id="report-data" type="application/json">(.*?)</script>', content, re.S).group(1)
            self.assertEqual(json.loads(embedded)["report"]["notes"], "</script><script>fake()</script>")
            self.assertNotIn("</script><script>fake()", embedded)
            self.assertEqual(list(Path(temp).glob("*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
