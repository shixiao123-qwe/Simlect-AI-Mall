"""Run offline tests and publish measured contract-test timing, never fake LLM metrics."""
import json
import math
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
reports=root/'evals/reports'
reports.mkdir(exist_ok=True)
xml=reports/'offline.xml'
result=subprocess.run([sys.executable,'-m','pytest',str(root/'evals/test_shopping.py'),'-q',f'--junitxml={xml}'],cwd=root)
if not xml.exists(): raise SystemExit(result.returncode or 1)
cases=list(ET.parse(xml).iter('testcase'))
times=sorted(float(c.get('time','0'))*1000 for c in cases)
failures=[{'case':c.get('name'),'category':'contract_test_failure','detail':(c.find('failure').get('message','') if c.find('failure') is not None else 'error')} for c in cases if c.find('failure') is not None or c.find('error') is not None]
summary={'mode':'offline_mocked_boundaries','samples':len(cases),'passed':len(cases)-len(failures),
 'mean_test_ms':sum(times)/len(times) if times else None,'p95_test_ms':times[math.ceil(len(times)*.95)-1] if times else None,
 'structured_llm_accuracy':None,'live_hard_constraint_rate':None,'live_mcp_success_rate':None,
 'metric_note':'Timings measure offline contract tests, NOT model response latency. Live metrics require a running stack and labeled data.',
 'baseline_comparison':None,'failures':failures}
(reports/'offline.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
raise SystemExit(result.returncode)
