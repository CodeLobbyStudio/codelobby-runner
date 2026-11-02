#!/usr/bin/env python3
"""Execute three small programs. No third-party host packages required."""
import base64,json,os,time,urllib.request
from pathlib import Path
BASE=os.environ.get('JUDGE0_URL','http://127.0.0.1:'+os.environ.get('JUDGE0_PORT','2359')).rstrip('/')
programs=[
 ('Java 21 with 40 MiB heap allocation',1001,'public class Main {public static void main(String[] a){byte[] b=new byte[40*1024*1024]; b[b.length-1]=1; System.out.println(Runtime.version().feature());System.out.println(b.length);}}','21\n41943040\n'),
 ('Python 3.12',1002,'type Pair = tuple[int,int]\np: Pair=(2,3)\nprint(sum(p))\n','5\n'),
 ('C++17 without caller compiler options',54,'#include <iostream>\n#include <optional>\nint main(){std::optional<int> n=5;std::cout<<*n<<"\\n";}','5\n')]
rows=[]
for name,language,source,expected in programs:
 payload={'language_id':language,'source_code':source,'expected_output':expected,'cpu_time_limit':2,'cpu_extra_time':0,'wall_time_limit':5,'memory_limit':125000,'enable_network':False}
 request=urllib.request.Request(BASE+'/submissions?base64_encoded=false&wait=false',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(request,timeout=15) as r:token=json.load(r)['token']
 deadline=time.monotonic()+60
 while True:
  with urllib.request.urlopen(BASE+'/submissions/'+token+'?base64_encoded=true',timeout=10) as r:obj=json.load(r)
  if obj['status']['id'] not in (1,2):break
  if time.monotonic()>deadline:raise RuntimeError('Polling timed out, termination unconfirmed: '+token)
  time.sleep(.5)
 for key in ('stdout','stderr','compile_output','message'):
  if obj.get(key) is not None:obj[key]=base64.b64decode(obj[key]).decode('utf-8',errors='replace')
 row={'test':name,'status':obj['status'],'stdout':obj.get('stdout'),'stderr':obj.get('stderr'),'compile_output':obj.get('compile_output'),'message':obj.get('message'),'memory_kib':obj.get('memory')}
 rows.append(row)
 Path('smoke-results.json').write_text(json.dumps(rows,indent=2))
 if obj['status']['id']!=3 or obj.get('stdout')!=expected:raise RuntimeError(json.dumps(row))
 print('PASS '+name,flush=True)
print('All execution checks passed.',flush=True)
