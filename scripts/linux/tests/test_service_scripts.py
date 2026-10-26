"""Exercise the real Bash scripts; replace only the host/service boundaries.

These tests must never start or stop a real systemd service.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
BASH = os.environ.get("AKIS_TEST_BASH") or (
    "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash")
)


class ServiceScriptsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="akis-ops-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.state = self.root / "state"
        self.state.write_text("inactive", encoding="utf-8")
        self.actions = self.root / "actions"
        self.curls = self.root / "curls"
        self.env = os.environ.copy()
        self.env.update({
            "FAKE_STATE_FILE": self.state.as_posix(),
            "FAKE_ACTIONS": self.actions.as_posix(),
            "FAKE_CURL_COUNT": self.curls.as_posix(),
            "FAKE_UID": "0", "FAKE_LOAD_STATE": "loaded",
            "FAKE_CURL_BODY": '{"status":"UP"}',
            "FAKE_PG_STATE": "active", "FAKE_NGINX_STATE": "active",
        })
        self.fake("id", '#!/bin/bash\nprintf "%s\\n" "$FAKE_UID"\n')
        # Use a real Python JSON parser, including on Git Bash/Windows.
        python = Path(sys.executable).as_posix()
        self.fake("python3", f"#!/bin/bash\nexec '{python}' \"$@\"\n")
        self.fake("systemctl", r'''#!/bin/bash
case "$1" in
  show)
    [[ "$*" == *"akis.service"* ]] || exit 99
    state=$(cat "$FAKE_STATE_FILE")
    printf 'LoadState=%s\nActiveState=%s\nSubState=%s\nMainPID=%s\n' \
      "$FAKE_LOAD_STATE" "$state" "$([[ "$state" == active ]] && echo running || echo dead)" \
      "$([[ "$state" == active ]] && echo 1234 || echo 0)"
    ;;
  start|stop)
    [[ "$*" == *"--no-block"* && "$*" == *"akis.service"* ]] || exit 99
    printf '%s\n' "$*" >> "$FAKE_ACTIONS"
    [[ "${FAKE_ACTION_FAIL:-0}" == 0 ]] || exit 1
    if [[ "$1" == start ]]; then printf '%s' "${FAKE_START_STATE:-active}" > "$FAKE_STATE_FILE"
    else printf '%s' "${FAKE_STOP_STATE:-inactive}" > "$FAKE_STATE_FILE"; fi
    ;;
  is-active)
    case "${*: -1}" in
      postgresql.service) state=$FAKE_PG_STATE ;;
      nginx.service) state=$FAKE_NGINX_STATE ;;
      *) exit 99 ;;
    esac
    printf '%s\n' "$state"
    [[ "$state" == active ]]
    ;;
  *) exit 99 ;;
esac
''')
        self.fake("curl", r'''#!/bin/bash
[[ "$*" == *"http://127.0.0.1:8080/actuator/health"* ]] || exit 99
[[ "$*" == *"--noproxy *"* ]] || exit 99
count=0
[[ ! -f "$FAKE_CURL_COUNT" ]] || count=$(cat "$FAKE_CURL_COUNT")
count=$((count + 1))
printf '%s' "$count" > "$FAKE_CURL_COUNT"
[[ "${FAKE_CURL_FAIL:-0}" == 0 ]] || exit 7
if (( count < ${FAKE_CURL_UP_AFTER:-1} )); then printf '{"status":"DOWN"}'
else printf '%s' "$FAKE_CURL_BODY"; fi
''')

    def fake(self, name, content):
        p = self.bin / name
        p.write_text(content, encoding="utf-8", newline="\n")
        p.chmod(0o755)

    def run_script(self, name, *args):
        launcher = 'export PATH="$(cygpath -u "$1"):$PATH"; shift; exec bash "$@"' if os.name == "nt" else 'export PATH="$1:$PATH"; shift; exec bash "$@"'
        return subprocess.run(
            [BASH, "-c", launcher, "test", self.bin.as_posix(),
             (SCRIPTS / name).as_posix(), *args],
            env=self.env, text=True, encoding="utf-8", errors="replace",
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12,
        )

    def assert_code(self, result, code):
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)

    def test_start_waits_until_health_up(self):
        self.env["FAKE_CURL_UP_AFTER"] = "2"
        r = self.run_script("start.sh", "5")
        self.assert_code(r, 0)
        self.assertEqual("active", self.state.read_text())
        self.assertEqual("2", self.curls.read_text())
        self.assertIn("UP", r.stdout)

    def test_start_does_not_restart_an_active_service(self):
        self.state.write_text("active")
        r = self.run_script("start.sh", "1")
        self.assert_code(r, 0)
        self.assertFalse(self.actions.exists())

    def test_start_does_not_accept_nested_up_as_root_health(self):
        self.env["FAKE_CURL_BODY"] = '{"status":"DOWN","components":{"db":{"status":"UP"}}}'
        self.assert_code(self.run_script("start.sh", "1"), 1)

    def test_start_rejects_malformed_health_json(self):
        self.env["FAKE_CURL_BODY"] = 'not json: "status":"UP"'
        self.assert_code(self.run_script("start.sh", "1"), 1)

    def test_start_reports_http_failure(self):
        self.env["FAKE_CURL_FAIL"] = "1"
        self.assert_code(self.run_script("start.sh", "1"), 1)

    def test_start_reports_service_crash(self):
        self.env["FAKE_START_STATE"] = "failed"
        self.assert_code(self.run_script("start.sh", "1"), 1)

    def test_start_reports_systemctl_failure(self):
        self.env["FAKE_ACTION_FAIL"] = "1"
        self.assert_code(self.run_script("start.sh", "1"), 1)

    def test_missing_unit_is_not_started(self):
        self.env["FAKE_LOAD_STATE"] = "not-found"
        self.assert_code(self.run_script("start.sh", "1"), 4)
        self.assertFalse(self.actions.exists())

    def test_unprivileged_start_has_no_side_effect(self):
        self.env["FAKE_UID"] = "1001"
        self.assert_code(self.run_script("start.sh", "1"), 4)
        self.assertFalse(self.actions.exists())

    def test_stop_waits_for_inactive(self):
        self.state.write_text("active")
        self.assert_code(self.run_script("stop.sh", "1"), 0)
        self.assertEqual("inactive", self.state.read_text())
        self.assertFalse(self.curls.exists())

    def test_stopping_inactive_service_is_harmless(self):
        self.assert_code(self.run_script("stop.sh", "1"), 0)
        self.assertFalse(self.actions.exists())

    def test_stop_reports_deadline_without_force_kill(self):
        self.state.write_text("active")
        self.env["FAKE_STOP_STATE"] = "deactivating"
        self.assert_code(self.run_script("stop.sh", "1"), 1)
        self.assertEqual(["stop --no-block akis.service"], self.actions.read_text().splitlines())

    def test_stop_reports_systemctl_failure(self):
        self.state.write_text("active")
        self.env["FAKE_ACTION_FAIL"] = "1"
        self.assert_code(self.run_script("stop.sh", "1"), 1)

    def test_unprivileged_stop_has_no_side_effect(self):
        self.state.write_text("active")
        self.env["FAKE_UID"] = "1001"
        self.assert_code(self.run_script("stop.sh", "1"), 4)
        self.assertFalse(self.actions.exists())

    def test_status_reports_healthy_backend_and_dependencies(self):
        self.state.write_text("active")
        r = self.run_script("status.sh")
        self.assert_code(r, 0)
        for value in ["1234", "UP", "PostgreSQL", "Nginx"]:
            self.assertIn(value, r.stdout)
        self.assertFalse(self.actions.exists())

    def test_status_can_be_read_without_root(self):
        self.env["FAKE_UID"] = "1001"
        self.state.write_text("active")
        self.assert_code(self.run_script("status.sh"), 0)

    def test_status_of_stopped_backend_is_distinct_and_skips_http(self):
        self.assert_code(self.run_script("status.sh"), 3)
        self.assertFalse(self.curls.exists())

    def test_status_does_not_hide_nginx_failure(self):
        self.state.write_text("active")
        self.env["FAKE_NGINX_STATE"] = "inactive"
        self.assert_code(self.run_script("status.sh"), 1)

    def test_status_does_not_hide_postgresql_failure(self):
        self.state.write_text("active")
        self.env["FAKE_PG_STATE"] = "failed"
        self.assert_code(self.run_script("status.sh"), 1)

    def test_status_reports_unhealthy_active_backend(self):
        self.state.write_text("active")
        self.env["FAKE_CURL_BODY"] = '{"status":"DOWN"}'
        self.assert_code(self.run_script("status.sh"), 1)

    def test_status_reports_failed_service(self):
        self.state.write_text("failed")
        self.assert_code(self.run_script("status.sh"), 1)

    def test_status_reports_unknown_unit(self):
        self.env["FAKE_LOAD_STATE"] = "not-found"
        self.assert_code(self.run_script("status.sh"), 4)

    def test_invalid_arguments_do_not_change_service(self):
        for name, args in [("start.sh", ["0"]), ("start.sh", ["601"]),
                           ("start.sh", ["abc"]), ("start.sh", ["1", "2"]),
                           ("stop.sh", ["-1"]), ("status.sh", ["unexpected"])]:
            with self.subTest(name=name, args=args):
                self.assert_code(self.run_script(name, *args), 2)
        self.assertFalse(self.actions.exists())

    def test_help_is_read_only_and_does_not_require_root(self):
        self.env["FAKE_UID"] = "1001"
        for name in ["start.sh", "stop.sh", "status.sh"]:
            with self.subTest(name=name):
                self.assert_code(self.run_script(name, "--help"), 0)
        self.assertFalse(self.actions.exists())


if __name__ == "__main__":
    unittest.main()
