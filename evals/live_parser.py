"""Opt-in real LLM evaluation. Reads local credentials, writes no credentials to reports."""
import asyncio
import json
import math
import os
from pathlib import Path
import sys
import time
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'Simlect-backend/Simlect-agent'))
from dotenv import dotenv_values
values=dotenv_values(root/'deploy/.env.local')
for key,value in values.items():
    if value is not None and key.startswith(('LLM_','MEMORY_LLM_')): os.environ[key]=value
os.environ.setdefault('SIMLECT_INTERNAL_TOKEN','offline-evaluation-no-business-access')
from app.services.shopping_parser import parse_shopping

async def run():
    cases=json.loads((root/'evals/parser_cases.json').read_text(encoding='utf-8'))
    results=[]
    for case in cases:
        started=time.perf_counter()
        req,error=await parse_shopping(case['text'],case.get('previous',{}))
        actual=req.model_dump(mode='json') if req else None
        matches=bool(actual is not None and all(actual.get(k)==v for k,v in case['expected'].items()))
        results.append({'id':case['id'],'passed':matches,'ms':(time.perf_counter()-started)*1000,
                        'failure':error or (None if matches else 'field_mismatch'),'actual':actual,'expected':case['expected']})
        print(f"{case['id']}: {'PASS' if matches else 'FAIL'}",flush=True)
    times=sorted(r['ms'] for r in results)
    categories={}
    for r in results:
        if r['failure']: categories[r['failure']]=categories.get(r['failure'],0)+1
    report={'mode':'real_llm_parser_only','model':os.environ.get('LLM_MODEL'),'samples':len(results),
            'structured_requirement_accuracy':sum(r['passed'] for r in results)/len(results),
            'mean_ms':sum(times)/len(times),'p95_ms':times[math.ceil(.95*len(times))-1],
            'failure_categories':categories,'hard_constraint_satisfaction':None,'mcp_success_rate':None,
            'baseline':None,'note':'Measured parser round-trip only. No Java/MCP/catalog execution; no prior comparable baseline.',
            'results':results}
    out=root/'evals/reports/live-parser.json';out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False,indent=2))

if __name__=='__main__': asyncio.run(run())
