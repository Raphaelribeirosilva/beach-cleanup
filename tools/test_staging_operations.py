import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


backup = module("backup_maintenance", ROOT / "deploy/backup-maintenance.py")
monitor = module("staging_monitor", ROOT / "deploy/monitor-staging.py")
staging = module("check_staging", ROOT / "tools/check_staging.py")


class StagingOperationsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="beachcleanup-ops-test-")
        self.root = Path(self.temporary.name)
        self.local = self.root / "local"
        self.offsite = self.root / "offsite"
        self.local.mkdir()
        self.offsite.mkdir()
        self.now = dt.datetime(2026, 10, 18, tzinfo=dt.timezone.utc)

    def tearDown(self):
        self.temporary.cleanup()

    def pair(self, age, suffix="ABC123"):
        stamp = (self.now - dt.timedelta(days=age)).strftime("%Y%m%dT%H%M%SZ")
        path = self.local / f"beachcleanup_staging-{stamp}-{suffix}.sql"
        path.write_bytes(b"-- controlled fixture, no credentials\n")
        path.with_name(path.name + ".sha256").write_text(f"{backup.digest(path)}  {path.name}\n", encoding="ascii")
        return path

    def test_copy_is_verified_portable_and_idempotent(self):
        source = self.pair(0)
        backup.publish(source, self.offsite)
        backup.publish(source, self.offsite)
        self.assertEqual(backup.verify(source), backup.verify(self.offsite / source.name))
        self.assertEqual(2, len(list(self.offsite.iterdir())))

    def test_retention_keeps_latest_two_and_ignores_unmanaged_files(self):
        newest, older, oldest = [self.pair(age) for age in [30, 40, 50]]
        unrelated = self.local / "another-database.sql"
        unrelated.write_text("untouched")
        self.assertEqual(1, backup.prune(self.local, "beachcleanup_staging", 14, now=self.now))
        self.assertTrue(newest.exists() and older.exists() and unrelated.exists())
        self.assertFalse(oldest.exists())

    def test_corruption_prevents_pruning_or_publishing(self):
        bad = self.pair(50)
        self.pair(0)
        self.pair(1)
        bad.write_text("corrupt")
        with self.assertRaises(ValueError):
            backup.prune(self.local, "beachcleanup_staging", 14, now=self.now)
        with self.assertRaises(ValueError):
            backup.publish(bad, self.offsite)
        self.assertTrue(bad.exists())
        self.assertFalse(list(self.offsite.iterdir()))

    def test_failed_copy_cleans_own_partial_without_deleting_local_data(self):
        source = self.pair(0)
        with patch.object(backup.shutil, "copyfileobj", side_effect=OSError("storage unavailable")):
            with self.assertRaises(OSError):
                backup.publish(source, self.offsite)
        self.assertTrue(source.exists())
        self.assertFalse(list(self.offsite.iterdir()))

    def test_missing_mount_never_prunes_local_backups(self):
        source = self.pair(60)
        with patch("sys.argv", ["backup-maintenance.py", str(self.local), str(self.offsite), "beachcleanup_staging"]), \
                patch.object(backup.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "mountpoint")):
            with self.assertRaises(subprocess.CalledProcessError):
                backup.main()
        self.assertTrue(source.exists())

    def test_monitor_detects_outage_and_old_or_future_backup_marker(self):
        marker = self.local / ".last-success"
        marker.write_text("1000")
        class Response(io.BytesIO):
            status = 200
        fetch = lambda *args, **kwargs: Response(b'{"status":"UP"}')
        self.assertEqual([], monitor.check(marker, now=1001, fetch=fetch))
        self.assertEqual(["backup_age"], monitor.check(marker, now=1000 + 27*3600, fetch=fetch))
        self.assertEqual(["backup_age"], monitor.check(marker, now=0, fetch=fetch))
        def outage(*args, **kwargs):
            raise OSError("down")
        self.assertEqual(["readiness"], monitor.check(marker, now=1001, fetch=outage))

    def test_allowed_and_blocked_networks_are_separate_evidence(self):
        def allowed(url):
            return (200, '<main id="workspace">') if url.endswith("/") else (403 if "actuator" in url else 401, "")
        self.assertTrue(staging.check("https://staging.acme.test", "allowed", allowed, lambda _: 30)["passed"])
        self.assertTrue(staging.check("https://staging.acme.test", "blocked", lambda _: (403,""), lambda _: 30)["passed"])
        self.assertFalse(staging.check("https://staging.acme.test", "blocked", allowed, lambda _: 30)["passed"])

    def test_tls_expiry_errors_and_placeholders_never_pass(self):
        for url in ["http://staging.acme.test", "https://homologacao.example.com", "https://user:secret@staging.acme.test", "https://staging.acme.test/?token=secret"]:
            with self.assertRaises(ValueError):
                staging.check(url,"allowed")
        report = staging.check("https://staging.acme.test","blocked",lambda _: (403,""),lambda _: 2)
        self.assertFalse(report["passed"])

    def test_shell_syntax(self):
        bash = shutil.which("bash")
        if not bash:
            candidate = Path("C:/Program Files/Git/bin/bash.exe")
            bash = str(candidate) if candidate.exists() else None
        self.assertIsNotNone(bash, "Install Bash to validate deployment scripts")
        for script in (ROOT / "deploy").glob("*.sh"):
            result = subprocess.run([bash,"-n",script.as_posix()],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)


if __name__ == "__main__":
    unittest.main()
