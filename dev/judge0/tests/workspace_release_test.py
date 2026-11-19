"""Check release verification against Linux filesystem fixtures, not real cgroups or mounts."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == "linux" and os.geteuid() == 0, "requires an isolated Linux root container")
class WorkspaceReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="codelobby-release-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "workspaces"
        self.boxbase = self.root / "isolate"
        self.cgroup = self.root / "cgroups"
        self.runtime = self.root / "runtime"
        for directory in (self.base, self.boxbase, self.cgroup, self.runtime):
            directory.mkdir(mode=0o700)
        (self.runtime / "cgroup").write_text(str(self.cgroup) + "\n")
        source = (ROOT / "image/workspace-helper.sh").read_text()
        # Replace only fixed deployment paths. The verification and rejection logic is unchanged.
        replacements = {
            "/var/local/lib/codelobby-workspaces": str(self.base),
            "/var/local/lib/isolate": str(self.boxbase),
            "/sys/fs/cgroup/codelobby-runner-judge0-v1": str(self.cgroup),
            "/run/isolate/cgroup": str(self.runtime / "cgroup"),
            "/run/codelobby-workspace-locks": str(self.root / "locks"),
            'for path in /var /var/local /var/local/lib "$boxbase"':
                'for path in "' + str(self.root) + '" "$boxbase"',
        }
        for original, fixture in replacements.items():
            if original not in source:
                raise AssertionError("Workspace helper fixture requires review")
            source = source.replace(original, fixture)
        self.helper = self.root / "workspace-helper.sh"
        self.helper.write_text(source)

    def verify(self, operation="verify-released"):
        return subprocess.run(["bash", str(self.helper), operation, "7"],
                              capture_output=True, text=True)

    def test_absent_artifacts_allow_release(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_leftover_workspace_or_isolate_directory_blocks_release(self):
        for parent in (self.base, self.boxbase):
            with self.subTest(parent=parent.name):
                artifact = parent / "7"
                artifact.mkdir()
                self.assertNotEqual(self.verify().returncode, 0)
                self.assertTrue(artifact.is_dir())
                artifact.rmdir()

    def test_leftover_cgroup_blocks_release_without_removing_it(self):
        group = self.cgroup / "box-7"
        group.mkdir()
        self.assertNotEqual(self.verify().returncode, 0)
        self.assertTrue(group.is_dir())

    def test_dangling_symlinks_block_release(self):
        for artifact in (self.base / "7", self.boxbase / "7", self.cgroup / "box-7"):
            with self.subTest(artifact=str(artifact)):
                artifact.symlink_to(self.root / "missing")
                self.assertNotEqual(self.verify().returncode, 0)
                self.assertTrue(artifact.is_symlink())
                artifact.unlink()

    def test_unknown_cgroup_root_blocks_release(self):
        (self.runtime / "cgroup").write_text("/unknown\n")
        self.assertNotEqual(self.verify().returncode, 0)


if __name__ == "__main__":
    unittest.main()
