"""Lifecycle regressions with a fake Docker CLI. No daemon or real secrets required."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StartupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="codelobby-startup-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / "project with spaces"
        self.project.mkdir()
        for name in ("start.sh", "stop.sh", "judge0.conf.example"):
            shutil.copyfile(ROOT / name, self.project / name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "docker.jsonl"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        FAKE_DOCKER_LOG=str(self.log))
        for name in ("JUDGE0_CONFIG_FILE", "JUDGE0_PORT", "COMPOSE_PROJECT_NAME",
                     "FAKE_ENGINE", "FAKE_SECURITY", "FAKE_INSPECT", "FAKE_UNAME"):
            self.env.pop(name, None)
        self.fake("docker", '''import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_DOCKER_LOG"], "a") as log:
    log.write(json.dumps(args) + "\\n")
if args[:2] == ["info", "--format"]:
    print(os.environ.get("FAKE_ENGINE", "linux 2") if "OSType" in args[2]
          else os.environ.get("FAKE_SECURITY", "name=seccomp"))
elif args[:2] == ["image", "inspect"]:
    sys.exit(int(os.environ.get("FAKE_INSPECT", "0")))
''')
        self.fake("openssl", '''import secrets
print(secrets.token_hex(32))
''')
        self.fake("uname", '''import os
print(os.environ.get("FAKE_UNAME", "Linux"))
''')

    def fake(self, name, code):
        path = self.bin / name
        path.write_text("#!/usr/bin/env python3\n" + code)
        path.chmod(0o755)

    def invoke(self, script="start.sh"):
        return subprocess.run(["bash", str(self.project / script)], cwd=self.root,
                              env=self.env, text=True, capture_output=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def compose(self):
        return [args for args in self.calls() if args[0] == "compose" and "--env-file" in args]

    def test_fresh_start_generates_independent_private_secrets_and_applies_run_overlay(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        config = self.project / "judge0.conf"
        entries = dict(line.split("=", 1) for line in config.read_text().splitlines()
                       if "=" in line and not line.startswith("#"))
        secrets = [entries[key] for key in ("POSTGRES_PASSWORD", "REDIS_PASSWORD", "SECRET_KEY_BASE")]
        self.assertEqual(len(set(secrets)), 3)
        for value in secrets:
            self.assertRegex(value, r"^[0-9a-f]{64}$")
            self.assertNotIn(value, result.stdout + result.stderr)
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.compose(), [["compose", "--env-file", str(config), "-f", "compose.yaml",
                                          "-f", "compose.reports.yaml", "up", "-d", "--wait",
                                          "--wait-timeout", "180"]])

    def test_repeated_start_preserves_original_configuration(self):
        self.assertEqual(self.invoke().returncode, 0)
        config = self.project / "judge0.conf"
        original = config.read_bytes()
        self.assertEqual(self.invoke().returncode, 0)
        self.assertEqual(config.read_bytes(), original)

    def test_custom_config_path_with_spaces_is_used_for_start_and_stop(self):
        config = self.root / "private settings.conf"
        self.env["JUDGE0_CONFIG_FILE"] = str(config)
        self.assertEqual(self.invoke().returncode, 0)
        self.assertEqual(self.invoke("stop.sh").returncode, 0)
        for call in self.compose():
            self.assertEqual(call[2], str(config))
            self.assertIn("compose.reports.yaml", call)
        self.assertEqual(self.compose()[-1][-1], "stop")
        self.assertNotIn("--volumes", self.compose()[-1])
        self.assertTrue(config.exists())

    def test_incompatible_engines_fail_before_configuration_or_start(self):
        for engine, security in (("windows 2", ""), ("linux 1", ""), ("linux 2", "name=rootless")):
            with self.subTest(engine=engine, security=security):
                self.env.update(FAKE_ENGINE=engine, FAKE_SECURITY=security)
                self.assertNotEqual(self.invoke().returncode, 0)
                self.assertFalse((self.project / "judge0.conf").exists())
                self.assertFalse(self.compose())

    def test_git_bash_is_rejected_before_contacting_docker(self):
        self.env["FAKE_UNAME"] = "MINGW64_NT-10.0"
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("WSL2", result.stderr)
        self.assertEqual(self.calls(), [])

    def test_invalid_existing_config_is_not_overwritten_and_services_do_not_start(self):
        config = self.project / "judge0.conf"
        config.write_text("POSTGRES_PASSWORD=\n")
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertEqual(config.read_text(), "POSTGRES_PASSWORD=\n")
        self.assertFalse(self.compose())

    def test_stop_without_private_config_does_not_contact_docker(self):
        self.assertNotEqual(self.invoke("stop.sh").returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_missing_image_is_pulled_for_amd64(self):
        self.env["FAKE_INSPECT"] = "1"
        self.assertEqual(self.invoke().returncode, 0)
        pull = next(call for call in self.calls() if call[:2] == ["image", "pull"])
        self.assertEqual(pull[2:4], ["--platform", "linux/amd64"])
        self.assertIn("@sha256:", pull[-1])


if __name__ == "__main__":
    unittest.main()
