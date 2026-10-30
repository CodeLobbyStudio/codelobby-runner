import json, os, shlex, subprocess, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

class JavaBudgetTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root=Path(self.tmp.name)
        fake=root/'jvm'
        fake.write_text('#!/usr/bin/env python3\nimport json,os,sys\nprint(json.dumps({"args":sys.argv[1:],"injection":{k:os.environ.get(k) for k in ["JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"]}}))\n')
        fake.chmod(0o755)
        self.launcher=root/'launcher'
        text=(ROOT/'image/java-with-budget.sh').read_text()
        for mode in ['javac','java']:text=text.replace('/usr/local/codelobby/jdk21/bin/'+mode,shlex.quote(str(fake)))
        self.launcher.write_text(text)

    def invoke(self, mode, limit):
        env={**os.environ,'JAVA_TOOL_OPTIONS':'untrusted','JDK_JAVA_OPTIONS':'untrusted','_JAVA_OPTIONS':'untrusted'}
        env.pop('CODELOBBY_MEMORY_LIMIT_KIB',None)
        if limit is not None:env['CODELOBBY_MEMORY_LIMIT_KIB']=limit
        return subprocess.run(['/bin/bash',str(self.launcher),mode,'Main'],env=env,text=True,capture_output=True)

    def test_heap_tracks_effective_execution_memory(self):
        for limit,heap in [('128000','77'),('64000','14')]:
            r=self.invoke('java',limit)
            self.assertEqual(r.returncode,0,r.stderr)
            obj=json.loads(r.stdout)
            self.assertIn('-Xmx'+heap+'m',obj['args'])
            self.assertIn('-XX:MaxNewSize=4m',obj['args'])
            self.assertTrue(all(v is None for v in obj['injection'].values()))
            self.assertEqual(obj['args'][-1],'Main')

    def test_compiler_has_its_own_larger_budget(self):
        r=self.invoke('javac','500000')
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('-J-Xmx424m',json.loads(r.stdout)['args'])

    def test_small_budget_does_not_use_a_large_minimum_heap(self):
        self.assertIn('-Xmx8m',json.loads(self.invoke('java','16000').stdout)['args'])

    def test_missing_invalid_or_injected_budget_is_rejected(self):
        for limit in [None,'','0','-1','128000;echo unsafe','99999999']:
            self.assertNotEqual(self.invoke('java',limit).returncode,0)

    def test_unknown_launcher_is_rejected(self):
        self.assertNotEqual(self.invoke('anything','128000').returncode,0)

if __name__=='__main__':unittest.main()
