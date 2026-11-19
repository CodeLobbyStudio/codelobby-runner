"""Linux root fixtures for worker-state initialization. No production volume is touched."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == "linux" and os.geteuid() == 0, "requires an isolated Linux root container")
class WorkerStateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="codelobby-worker-state-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"
        source = (ROOT / "image/initialize-worker-state.sh").read_text()
        source = source.replace("/var/lib/codelobby-worker-state", str(self.state))
        source = source.replace("for parent in /var /var/lib", 'for parent in "' + str(self.root) + '"')
        self.helper = self.root / "initialize.sh"
        self.helper.write_text(source)
        self.uid = int(subprocess.check_output(["id", "-u", "judge0"]))
        self.gid = int(subprocess.check_output(["id", "-g", "judge0"]))

    def invoke(self):
        return subprocess.run(["bash", str(self.helper)], text=True, capture_output=True)

    def test_initializes_empty_volume_with_private_worker_subdirectories(self):
        self.state.mkdir(mode=0o755)
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state.stat().st_uid, 0)
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o711)
        for name in ("jobs", "slots"):
            stat = (self.state / name).stat()
            self.assertEqual((stat.st_uid, stat.st_gid, stat.st_mode & 0o777), (self.uid, self.gid, 0o700))

    def test_repeated_initialization_preserves_entries_and_ownership(self):
        self.assertEqual(self.invoke().returncode, 0)
        entries = []
        for name in ("jobs", "slots"):
            entry = self.state / name / "existing"
            entry.write_text("do not remove")
            os.chown(entry, self.uid, self.gid)
            entry.chmod(0o600)
            entries.append(entry)
        self.assertEqual(self.invoke().returncode, 0)
        for entry in entries:
            self.assertEqual(entry.read_text(), "do not remove")
            self.assertEqual(entry.stat().st_uid, self.uid)
            self.assertEqual(entry.stat().st_mode & 0o777, 0o600)

    def test_rejects_writable_parent_without_modifying_existing_data(self):
        self.state.mkdir(mode=0o777)
        self.state.chmod(0o777)
        entry = self.state / "existing"
        entry.write_text("keep")
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertEqual(entry.read_text(), "keep")
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o777)

    def test_rejects_symlinked_subdirectory_without_touching_target(self):
        self.assertEqual(self.invoke().returncode, 0)
        (self.state / "jobs").rmdir()
        target = self.root / "target"
        target.mkdir()
        (self.state / "jobs").symlink_to(target)
        mode = target.stat().st_mode
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertEqual(target.stat().st_mode, mode)

    def test_wrong_owner_or_permissions_are_not_silently_repaired(self):
        self.assertEqual(self.invoke().returncode, 0)
        jobs = self.state / "jobs"
        os.chown(jobs, 0, 0)
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertEqual(jobs.stat().st_uid, 0)
        os.chown(jobs, self.uid, self.gid)
        jobs.chmod(0o750)
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertEqual(jobs.stat().st_mode & 0o777, 0o750)


if __name__ == "__main__":
    unittest.main()
