"""Opt-in local artifact-capture probes. The experimental worker overlay must already be running."""
import base64
import hashlib
import io
import json
import os
import re
import uuid
from pathlib import Path
import subprocess
import time
import unittest
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "codelobby:java-artifacts:prototype:v1:"


@unittest.skipUnless(os.environ.get("CODELOBBY_TEST_JAVA_ARTIFACTS") == "true", "local artifact prototype only")
class JavaArtifactsIntegrationTest(unittest.TestCase):
    def redis(self, *args):
        command = ["docker", "compose", "--env-file", os.environ.get("JUDGE0_CONFIG_FILE", "judge0.conf"),
                   "-f", "compose.yaml", "-f", "compose.reports.yaml", "-f", "compose.artifacts.experimental.yaml",
                   "exec", "-T", "redis", "sh", "-c",
                   'export REDISCLI_AUTH="$REDIS_PASSWORD"\nexec redis-cli --raw "$@"', "sh", *args]
        return subprocess.check_output(command, cwd=ROOT, timeout=15).decode().strip()

    def http(self, path, payload=None):
        request = urllib.request.Request("http://127.0.0.1:2359" + path,
                                         data=None if payload is None else json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def submit(self, scenario):
        solution = "class Solution { static int answer() { return 42; } }"
        if scenario == "compilation":
            solution = "class Solution { invalid syntax }"
        main = r'''
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
public class Main {
    public static void main(String[] args) throws Exception {
        if (Solution.answer() != 42) throw new AssertionError();
        Path path = Path.of(".codelobby/classes/Solution.class");
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        Files.write(path, new byte[]{1, 2, 3, 4});
        System.out.println(Files.isSymbolicLink(Path.of(".codelobby/classes/Linked.class")));
    }
}
'''
        extra = ""
        if scenario == "symlink":
            extra = "ln -s /etc/passwd .codelobby/classes/Linked.class\ntest -L .codelobby/classes/Linked.class\n"
        if scenario == "oversized":
            extra = "dd if=/dev/zero of=.codelobby/classes/Large.class bs=262145 count=1 status=none\n"
        build = """#!/bin/bash
# CODELOBBY_BUILD_LIMITS 10000 20000
set -euo pipefail
mkdir -p .codelobby/classes
/usr/local/bin/java-with-budget.sh javac -proc:none -encoding UTF-8 -d .codelobby/classes Solution.java Main.java
""" + extra
        run = """#!/bin/bash
set -euo pipefail
exec /usr/local/bin/java-with-budget.sh java -classpath .codelobby/classes Main
"""
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for path, source in {"Solution.java": solution, "Main.java": main, "compile": build, "run": run}.items():
                archive.writestr(path, source)
        receipt = self.http("/submissions?base64_encoded=false&wait=false", {
            "language_id": 89, "additional_files": base64.b64encode(buffer.getvalue()).decode(),
            "cpu_time_limit": 2, "cpu_extra_time": 0, "wall_time_limit": 5, "memory_limit": 125000,
            "enable_network": False, "number_of_runs": 1,
        })
        token = receipt["token"]
        self.addCleanup(lambda: self.redis("ZREM", PREFIX + "index", PREFIX + token))
        self.addCleanup(lambda: self.redis("DEL", PREFIX + token))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            result = self.http("/submissions/" + token + "?base64_encoded=false")
            if result["status"]["id"] > 2:
                return token, result
            time.sleep(0.5)
        self.fail("Controlled artifact probe did not finish")

    def test_snapshot_precedes_runtime_mutation_and_survives_workspace_cleanup(self):
        token, result = self.submit("accepted")
        self.assertEqual(3, result["status"]["id"])
        stored = json.loads(self.redis("GET", PREFIX + token))
        self.assertEqual("AVAILABLE", stored["state"])
        raw = base64.b64decode(stored["snapshot"], validate=True)
        self.assertEqual(stored["sha256"], hashlib.sha256(raw).hexdigest())
        classes = {entry["path"]: base64.b64decode(entry["contentBase64"], validate=True)
                   for entry in json.loads(raw)["files"]}
        self.assertEqual({"Main.class", "Solution.class"}, set(classes))
        self.assertEqual(result["stdout"].splitlines()[0], hashlib.sha256(classes["Solution.class"]).hexdigest())
        self.assertNotEqual(bytes([1, 2, 3, 4]), classes["Solution.class"])
        self.assertGreater(int(self.redis("TTL", PREFIX + token)), 0)
        self.assertLessEqual(int(self.redis("TTL", PREFIX + token)), 300)

    def test_symlink_rejection_does_not_change_successful_evaluation(self):
        token, result = self.submit("symlink")
        self.assertEqual(3, result["status"]["id"])
        stored = json.loads(self.redis("GET", PREFIX + token))
        if stored["state"] == "AVAILABLE":
            self.assertEqual("false", result["stdout"].splitlines()[1])
            snapshot = json.loads(base64.b64decode(stored["snapshot"], validate=True))
            self.assertNotIn("Linked.class", [entry["path"] for entry in snapshot["files"]])
        else:
            self.assertEqual({"state": "MISSING"}, stored)

    def test_oversized_artifacts_do_not_change_successful_evaluation(self):
        token, result = self.submit("oversized")
        self.assertEqual(3, result["status"]["id"])
        self.assertEqual({"state": "TOO_LARGE"}, json.loads(self.redis("GET", PREFIX + token)))

    def test_cache_bounds_and_expiry_policy(self):
        source = (ROOT / "image" / "java-artifact-capture.rb").read_text()
        script = re.search(r"STORE = <<~LUA\n(.*?)^  LUA", source, re.MULTILINE | re.DOTALL).group(1)
        prefix = "codelobby:artifact-policy-test:" + uuid.uuid4().hex + ":"
        index = prefix + "index"
        keys = [prefix + str(number) for number in range(34)]
        self.addCleanup(lambda: self.redis("DEL", index, *keys))
        now = time.time()
        for number in range(33):
            self.redis("EVAL", script, "2", index, keys[number], str(now + number / 1000), '{"state":"MISSING"}')
        self.assertEqual("32", self.redis("ZCARD", index))
        self.assertEqual("", self.redis("GET", keys[0]))
        self.assertGreater(int(self.redis("TTL", keys[32])), 0)
        self.assertLessEqual(int(self.redis("TTL", keys[32])), 300)
        self.redis("EVAL", script, "2", index, keys[33], str(now + 301), '{"state":"MISSING"}')
        self.assertEqual("1", self.redis("ZCARD", index))
        self.assertEqual("", self.redis("GET", keys[32]))

    def test_compilation_failure_has_no_captured_artifact(self):
        token, result = self.submit("compilation")
        self.assertEqual(6, result["status"]["id"])
        self.assertEqual("", self.redis("GET", PREFIX + token))


if __name__ == "__main__":
    unittest.main()
