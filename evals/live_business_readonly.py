"""Live graph business branch -> remote MCP -> Java; no order/payment writes.

Default: scripted requirements, real tools/services, isolated in-memory sessions.
--with-model opts into paid real structured parsing. Neither mode tests browser
delivery or the complete graph entry/persistence/cleanup lifecycle.
"""
import argparse
import asyncio
import json
import math
import os
import sys
import time
from pathlib import Path
from unittest.mock import AsyncMock, patch
import httpx
from dotenv import dotenv_values

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'Simlect-backend/Simlect-agent'))

async def run(with_model=False):
    config=dotenv_values(ROOT/'deploy/.env.local')
    for key,value in config.items():
        if value is not None and (key.startswith(('LLM_','MEMORY_LLM_')) or key in ('SIMLECT_INTERNAL_TOKEN','VECTOR_SEARCH_ENABLED')):
            os.environ[key]=value
    os.environ['INTERNAL_READ_BACKEND']='http'
    from app.graph import nodes
    from app.memory.models import SessionMemory
    from app.memory.session_memory_service import session_memory_service
    from app.models.shopping import ShoppingPatch,merge_requirement
    from app.services import shopping_turn
    from app.services.redis_service import redis_service
    from app.services.mcp_streamable_client import mcp_streamable_client
    from app.services.tool_invoke_result import parse_tool_wire
    from app.services.request_context import trace_scope,get_trace_id
    memory={};results=[];tool_results=[]
    async def load(user,_redis): return memory.setdefault(user,SessionMemory(user_id=user))
    async def save(row,_redis): memory[row.user_id]=row
    original=mcp_streamable_client.call_tool
    async def traced_tool(name,args):
        result=parse_tool_wire(await original(name,args))
        tool_results.append({'tool':name,'status':result.status,'error_code':result.error_code,'trace_id':get_trace_id()})
        return result.to_wire()
    async def scripted_parser(text,previous):
        if '重新开始' in text: values={'reset':True,'category':'不存在的商品类别XYZ123'}
        elif '500' in text: values={'max_price':500}
        else: values={'category':'厨具','max_price':300}
        return merge_requirement(previous,ShoppingPatch(**values)),None
    async def turn(name,text,budget=None):
        start=time.perf_counter()
        with trace_scope():
            row=await nodes.agent_loop_node({'user_id':'evalread0000001','message_id':1,'agent_msg':{},'user_text':text,'intent':'PRODUCT_SEARCH'})
            products=json.loads(row.get('assistant_cards') or '[]')
            if budget is not None:
                assert products,'Expected imported public catalog candidates'
                assert all(float(p['verified']['price'])<=budget and p['verified']['stock']>0 for p in products)
            results.append({'name':name,'passed':True,'trace_id':get_trace_id(),'products':len(products),'ms':round((time.perf_counter()-start)*1000,2)})
            return products
    # Keep evaluation session state outside real account databases and Redis.
    with patch.object(redis_service,'_client',object()),patch.object(session_memory_service,'load',load),patch.object(session_memory_service,'save',save),patch.object(nodes.rt,'is_cancelled',AsyncMock(return_value=False)),patch.object(mcp_streamable_client,'call_tool',traced_tool):
        parser_context=patch.object(shopping_turn,'parse_shopping',scripted_parser) if not with_model else patch.object(shopping_turn,'parse_shopping',shopping_turn.parse_shopping)
        with parser_context:
            await turn('recommend-budget-300','推荐300元以内的厨具',300)
            previous=await turn('change-budget-500','预算改为500元以内，其他条件不变',500)
            compared=await turn('compare-current-skus','比较第一款和第二款',500)
            assert len(compared)==2
            assert [p['productId'] for p in compared]==[p['productId'] for p in previous[:2]]
            assert [p['verified']['skuHash'] for p in compared]==[p['verified']['skuHash'] for p in previous[:2]]
            before_empty=len(tool_results)
            assert not await turn('empty-query','重新开始，寻找不存在的商品类别XYZ123')
            assert len(tool_results)>before_empty and tool_results[-1]['status']=='empty','An empty catalog is not a parser/transport failure'
            call_count=len(tool_results)
            assert not await turn('empty-turn-clears-comparison','比较第一款和第二款')
            assert len(tool_results)==call_count,'Stale products must not be compared'
        policy=parse_tool_wire(await original('QUERY_AFTER_SALE_POLICY',{'question':'退货运费谁出，退款多久到账？'}))
        assert 'AS-004' in policy.content and '不能据此承诺' in policy.content
    # Read-only storefront pagination verifies the same externally visible contract.
    async with httpx.AsyncClient(timeout=20) as client:
        ids=[];total=None
        for page in range(1,101):
            response=await client.post('http://127.0.0.1:6001/api/product/loadProduct',data={'pageNo':page,'includeRecommended':'true'})
            data=response.json();assert data.get('code')==200
            data=data['data'];total=data['totalCount'];ids.extend(p['productId'] for p in data['list'])
            if page>=data['pageTotal']:break
        assert len(ids)==len(set(ids))==total,'Pagination overlaps or misses products'
    durations=sorted(r['ms'] for r in results)
    report={'mode':'live_graph_branch_mcp_java_with_real_model' if with_model else 'live_graph_branch_mcp_java_scripted_requirements',
        'sessions':'isolated in-memory; no real session/confirmation writes','cases':results,'tools':tool_results,
        'catalog_unique_count':len(ids),'policy_limits_verified':True,
        'mean_turn_ms':sum(durations)/len(durations),'p95_turn_ms':durations[math.ceil(.95*len(durations))-1],
        'baseline':None,'note':'Graph business branch and real MCP/Java are exercised. Not browser E2E or a full transaction test; no claimed general LLM accuracy.'}
    out=ROOT/'evals/reports'/('live-business-readonly-model.json' if with_model else 'live-business-readonly-scripted.json');out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--with-model',action='store_true',help='Uses configured model API and may incur charges')
    enabled=parser.parse_args().with_model
    try:
        asyncio.run(run(enabled))
    except Exception as exc:
        out=ROOT/'evals/reports'/('live-business-readonly-model.json' if enabled else 'live-business-readonly-scripted.json')
        out.parent.mkdir(exist_ok=True)
        report={'mode':'live_business_readonly','with_model':enabled,'passed':False,'failure_category':type(exc).__name__,
                'note':'Online flow did not complete. No success metrics emitted; inspect service logs using trace IDs.'}
        out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False));raise SystemExit(1)
