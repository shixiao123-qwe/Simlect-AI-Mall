"""Read-only MCP→Java evaluation against the six DEMOAI catalog fixtures.

No orders, payment calls, stock mutations, model calls or user messages are created.
Run after importing sql/21_demo_ai_products.sql and starting the current stack.
"""
import asyncio
import json
import math
import os
from pathlib import Path
import sys
import time
from decimal import Decimal
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'Simlect-backend/Simlect-agent'))
from dotenv import dotenv_values
for k,v in dotenv_values(root/'deploy/.env.local').items():
    if v is not None and k.startswith(('SIMLECT_INTERNAL_TOKEN','LLM_','EMBEDDING_','VECTOR_SEARCH_ENABLED')):
        os.environ[k]=v
os.environ['JAVA_WEB_URL']='http://127.0.0.1:8080'
os.environ['MCP_SERVER_URL']='http://127.0.0.1:7060'
from app.services.mcp_streamable_client import mcp_streamable_client
from app.services.tool_invoke_result import parse_tool_wire

CATALOG={
 'DEMOAI000000001':{'price':Decimal('199'),'new':True},
 'DEMOAI000000002':{'price':Decimal('399'),'new':True},
 'DEMOAI000000005':{'price':Decimal('129'),'new':False},
 'DEMOAI000000006':{'price':Decimal('299'),'new':True}}

async def run():
    # Refuse to report success against a different/mutated fixture catalog.
    from app.services.java_internal_client import JavaInternalClient,normalize_keys
    class StockFixtureClient(JavaInternalClient):
        def _base(self): return 'http://127.0.0.1:8102'
    snapshot=normalize_keys(await JavaInternalClient(timeout=10).post_json('/internal/product/snapshotBatch',{'productIds':[f'DEMOAI{i:09}' for i in range(1,7)]}))
    stocks=normalize_keys(await StockFixtureClient(timeout=10).post_json('/internal/stock/readBatch',{'productIds':[f'DEMOAI{i:09}' for i in range(1,7)]}))
    inventory={r['product_id']:r['stock'] for r in stocks}
    expected_inventory={f'DEMOAI{i:09}':q for i,q in enumerate([10,10,0,10,5,8],1)}
    assert inventory==expected_inventory,'Fixture inventory changed; restore explicitly or update labels before evaluating.'
    assert len(snapshot['products'])==6,'Demo catalog incomplete'
    for row in snapshot['skus']:
        if row['product_id'] in CATALOG:
            assert Decimal(str(row['price']))==CATALOG[row['product_id']]['price'],'Fixture SKU price changed'
    results=[];valid_cards=total_cards=transport_ok=0
    for budget in [100,129,199,200,258,259,299,300,399,500]:
        for new_only in [False,True]:
            req={'category':'蓝牙耳机','max_price':str(budget),
                 'hard_attributes':[{'name':'成色','value':'全新'}] if new_only else []}
            expected={pid for pid,item in CATALOG.items() if item['price']<=budget and (not new_only or item['new'])}
            started=time.perf_counter()
            raw=await mcp_streamable_client.call_tool('RECOMMEND_PRODUCTS',{'userId':'9000000001','requirements':req})
            ms=(time.perf_counter()-started)*1000
            transport=not raw.startswith(('【工具调用失败】','【工具调用超时】','【工具执行失败】'))
            transport_ok+=int(transport)
            result=parse_tool_wire(raw)
            cards=json.loads(result.assistant_cards) if result.assistant_cards else []
            got={c['productId'] for c in cards}
            for card in cards:
                total_cards+=1
                valid_cards+=int(card['productId'] in expected and Decimal(str(card['minPrice']))==CATALOG[card['productId']]['price'])
            passed=transport and got==expected and not any(w in result.content for w in ('暂不可用','操作失败'))
            results.append({'id':f'budget-{budget}-new-{new_only}','ms':ms,'transport_success':transport,
                            'passed':passed,'failure':None if passed else 'transport_or_catalog_mismatch',
                            'expected_ids':sorted(expected),'actual_ids':sorted(got)})
    times=sorted(r['ms'] for r in results)
    report={'mode':'live_mcp_java_fixed_demo_catalog','samples':len(results),
            'passed':sum(r['passed'] for r in results),'mcp_transport_success_rate':transport_ok/len(results),
            'hard_constraint_satisfaction_rate':valid_cards/total_cards if total_cards else None,
            'evaluated_returned_cards':total_cards,'valid_cards':valid_cards,
            'mean_ms':sum(times)/len(times),'p95_ms':times[math.ceil(.95*len(times))-1],
            'failure_categories':{'transport_or_catalog_mismatch':sum(not r['passed'] for r in results)},
            'baseline':None,'note':'Read-only fixed demo catalog; not production traffic or full natural-language chat latency. No model called.',
            'results':results}
    path=root/'evals/reports/live-catalog.json';path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False,indent=2))

if __name__=='__main__':asyncio.run(run())
