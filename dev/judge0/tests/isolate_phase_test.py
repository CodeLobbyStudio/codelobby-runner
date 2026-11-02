import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "image" / "isolate-with-workspace.sh"

class IsolatePhaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="codelobby-phase-test-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        fake = root / "fake-isolate"
        fake.write_text("#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
        fake.chmod(0o755)
        self.shim = root / "shim.sh"
        text = SOURCE.read_text()
        assert text.count("real=/usr/local/bin/isolate") == 1
        self.shim.write_text(text.replace("real=/usr/local/bin/isolate", "real=" + shlex.quote(str(fake))))
        self.options = ["--cg", "-b", "7", "-t", "0.1", "-w", "0.2", "-x", "0.5", "--cg-mem=32000", "--run", "--", "/bin/bash", "run.sh"]

    def invoke(self, phase, options=None):
        env = os.environ.copy()
        if phase is None:
            env.pop("CODELOBBY_ISOLATE_PHASE", None)
        else:
            env["CODELOBBY_ISOLATE_PHASE"] = phase
        return subprocess.run(["/bin/bash", str(self.shim), *(options or self.options)], env=env, capture_output=True, text=True)

    def test_execution_limits_are_unchanged(self):
        result = self.invoke(None)
        self.assertEqual(result.returncode, 0)
        args = json.loads(result.stdout)
        expected = self.options.copy()
        index = expected.index("--")
        expected.insert(index, "-E")
        expected.insert(index + 1, "CODELOBBY_MEMORY_LIMIT_KIB=32000")
        expected.insert(index + 2, "--dir=/dev/shm=/var/local/lib/codelobby-workspaces/7/shm:rw")
        self.assertEqual(args, expected)

    def test_compilation_gets_separate_exact_budget(self):
        result = self.invoke("compile")
        self.assertEqual(result.returncode, 0)
        args = json.loads(result.stdout)
        for value in ["--time=10", "--wall-time=20", "--extra-time=0", "--cg-mem=500000"]:
            self.assertIn(value, args)
        for value in ["0.1", "0.2", "0.5", "--cg-mem=32000"]:
            self.assertNotIn(value, args)
        self.assertEqual(500000 * 1024, 512000000)
        self.assertIn("CODELOBBY_MEMORY_LIMIT_KIB=500000", args)

    def test_compiler_requires_cgroups(self):
        options = self.options.copy(); options.remove("--cg")
        self.assertNotEqual(self.invoke("compile", options).returncode, 0)

    def test_command_arguments_are_not_rewritten(self):
        result = self.invoke("compile", self.options + ["--time=99"])
        args = json.loads(result.stdout)
        self.assertEqual(args[-1], "--time=99")

    def test_unknown_phase_is_rejected(self):
        self.assertNotEqual(self.invoke("anything-else").returncode, 0)

    def test_non_execution_commands_are_passed_through(self):
        result = self.invoke(None, ["--version"])
        self.assertEqual(json.loads(result.stdout), ["--version"])

if __name__ == "__main__":
    unittest.main()
