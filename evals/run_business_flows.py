"""Run mocked-boundary workflows; report test timing, never invented live metrics."""
import json
import argparse
import math
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--junit-input',type=Path,help='Summarize an existing combined run without repeating tests')
    args=parser.parse_args()
    folder=ROOT/'evals/reports'/('business-'+str(time.time_ns()))
    folder.mkdir(parents=True)
    xml=args.junit_input or folder/'junit.xml'
    result=None
    if not args.junit_input:
        result=subprocess.run([sys.executable,'-m','pytest',str(ROOT/'evals/test_business_workflows.py'),
            '-q','-p','no:cacheprovider',f'--junitxml={xml}',f'--basetemp={folder / "tmp"}'],cwd=ROOT)
    cases=list(ElementTree.parse(xml).iter('testcase')) if xml.exists() else []
    if args.junit_input:
        cases=[c for c in cases if 'test_business_workflows' in c.get('classname','')]
    failures=[c for c in cases if c.find('failure') is not None or c.find('error') is not None]
    durations=sorted(float(c.get('time','0'))*1000 for c in cases)
    report={'mode':'offline_business_workflows_mocked_external_boundaries',
        'boundaries':['LLM','MCP transport','Elasticsearch','Java authority','Redis'],
        'samples':len(cases),'passed':len(cases)-len(failures),
        'mean_test_ms':sum(durations)/len(durations) if durations else None,
        'p95_test_ms':durations[math.ceil(.95*len(durations))-1] if durations else None,
        'live_llm_accuracy':None,'live_hard_constraint_rate':None,'live_mcp_success_rate':None,
        'failure_categories':{'workflow_assertion_or_setup':len(failures)},
        'failed_cases':[c.get('name') for c in failures],
        'baseline':None,'note':'Real business branches execute; external dependencies are controlled fakes. Timing is pytest duration, not user-perceived latency.'}
    (folder/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return (result.returncode if result else 0) or (1 if failures or not cases else 0)

if __name__=='__main__': raise SystemExit(main())
