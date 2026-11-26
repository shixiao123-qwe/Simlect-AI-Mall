"""Business-flow contracts through real graph branches/router/services.

External LLM, MCP transport, ES, Java and Redis are controlled fakes. These are
NOT live end-to-end accuracy/latency measurements and never change real orders.
"""
import asyncio
import copy
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'Simlect-backend/Simlect-agent'))
os.environ.setdefault('SIMLECT_INTERNAL_TOKEN', 'offline-business-contract-no-production-access')
from app.memory.models import SessionMemory
from app.services import shopping_parser, shopping_service, after_sale_service
from app.services.tool_invoke_result import ToolInvokeResult


@pytest.fixture
def flow(monkeypatch):
    from app.graph import nodes
    from app.memory.session_memory_service import session_memory_service
    from app.services.redis_service import redis_service
    from app.services.mcp_streamable_client import mcp_streamable_client
    from app.services.pending_action_service import pending_action_service
    from app.services.java_internal_client import JavaInternalClient

    env = SimpleNamespace(memory={}, java_calls=[], tool_calls=[], results=[], proposals={}, patches=[], fault=None)
    async def load(user, _redis):
        return env.memory.setdefault(user, SessionMemory(user_id=user))
    async def save(memory, _redis):
        env.memory[memory.user_id] = memory
    monkeypatch.setattr(session_memory_service, 'load', load)
    monkeypatch.setattr(session_memory_service, 'save', save)
    monkeypatch.setattr(nodes.rt, 'is_cancelled', AsyncMock(return_value=False))
    monkeypatch.setattr(redis_service, '_client', object())
    monkeypatch.setattr(redis_service, 'ensure_connected', AsyncMock())
    monkeypatch.setattr(redis_service, 'get_bound_message_id', AsyncMock(return_value=1))
    async def save_pending(token, pending, **_kw):
        env.proposals[token] = copy.deepcopy(pending)
    monkeypatch.setattr(redis_service, 'save_pending_action', save_pending)
    monkeypatch.setattr(pending_action_service, 'get_by_token', AsyncMock(side_effect=lambda token: env.proposals.get(token)))

    async def model_reply(_messages):
        return env.patches.pop(0) if env.patches else {}
    model = MagicMock()
    model.with_structured_output.return_value.ainvoke = model_reply
    monkeypatch.setattr(shopping_parser, 'create_memory_llm', lambda: model)
    monkeypatch.setattr(shopping_service, 'get_settings', lambda: SimpleNamespace(internal_read_backend='http', vector_search_enabled=False))
    products = [
        {'productId':'p1','productName':'轻享耳机','skuHash':'sku1','price':199,'stock':10,'attributes':{'成色':'全新','特点':'舒适'}},
        {'productId':'p2','productName':'舒适耳机','skuHash':'sku2','price':299,'stock':8,'attributes':{'成色':'全新'}},
        {'productId':'p3','productName':'游戏耳机','skuHash':'sku3','price':399,'stock':5,'attributes':{'成色':'全新','特点':'低延迟'}},
        {'productId':'p4','productName':'二手耳机','skuHash':'sku4','price':99,'stock':2,'attributes':{'成色':'二手'}},
    ]
    async def recall(_query, _limit):
        if env.fault == 'empty': return []
        return [p['productId'] for p in products]
    monkeypatch.setattr(shopping_service.rag_retriever, 'search_product_keyword_ids', recall)
    monkeypatch.setattr(shopping_service.rag_retriever, 'search_product_vector_ids', AsyncMock(return_value=[]))
    async def java(_client, path, body=None):
        env.java_calls.append((path, copy.deepcopy(body)))
        if env.fault == 'java_timeout': raise httpx.ReadTimeout('controlled Java timeout')
        if path.endswith('/ai/filter'):
            rows = [copy.deepcopy(p) for p in products if p['productId'] in body['productIds']]
            rows = [p for p in rows if (body.get('maxPrice') is None or p['price'] <= float(body['maxPrice']))]
            for attr in body.get('hardAttributes', []):
                if attr['operator'] == 'eq': rows = [p for p in rows if p['attributes'].get(attr['name']) == attr['value']]
            return rows
        assert path == '/internal/order/afterSale/eligibility'
        return {'eligible':True,'orderId':'20261124000000001DEMO0001','policyVersion':'demo-v3',
                'returnWindowStatus':'POLICY_MISSING','returnWindowReason':'缺少正式期限资料，需人工核实'}
    monkeypatch.setattr(JavaInternalClient, 'post_json', java)
    async def remote(tool, args):
        env.tool_calls.append((tool, copy.deepcopy(args)))
        if env.fault == 'mcp_timeout':
            result = ToolInvokeResult(content='工具暂不可用',status='transport_error',error_code='MCP_TIMEOUT',retryable=True)
        elif tool == 'RECOMMEND_PRODUCTS': result = await shopping_service.recommend(args['requirements'])
        elif tool == 'COMPARE_PRODUCTS': result = await shopping_service.compare(args['productIds'],args.get('preferences',[]),args.get('requirements'),args.get('skuHashes'))
        elif tool == 'QUERY_AFTER_SALE_POLICY': result = ToolInvokeResult(content=after_sale_service.query_policy(args['question']))
        elif tool == 'CHECK_AFTER_SALE_ELIGIBILITY': result = await after_sale_service.check(args['userId'],args['orderItemId'],args['type'])
        elif tool == 'CREATE_AFTER_SALE_REQUEST': result = await after_sale_service.propose(args['userId'],args['orderItemId'],args['type'],args['reason'])
        else: raise AssertionError('Unexpected tool '+tool)
        env.results.append(result)
        return result.to_wire()
    monkeypatch.setattr(mcp_streamable_client, 'call_tool', remote)
    async def turn(text, intent='PRODUCT_SEARCH', user='workflow-user'):
        return await nodes.agent_loop_node({'user_id':user,'message_id':1,'agent_msg':{},'user_text':text,'intent':intent})
    env.turn = turn
    return env


def cards(result):
    return json.loads(result.get('assistant_cards') or '[]')


def test_recommendation_crosses_graph_parser_router_java_and_preserves_evidence(flow, record_property):
    flow.patches.append({'category':'蓝牙耳机'})
    result=asyncio.run(flow.turn('推荐300元以内蓝牙耳机，不要二手'))
    assert result['shopping_handled'] and result['route']=='finalize'
    assert {p['productId'] for p in cards(result)} == {'p1','p2'}
    assert all(float(p['verified']['price'])<=300 and p['verified']['stock']>0 for p in cards(result))
    assert flow.java_calls[0][1]['hardAttributes']==[{'name':'成色','value':'全新','operator':'eq'}]
    assert flow.tool_calls[0][1]['userId']=='workflow-user'
    record_property('scope','mocked-boundary-business-workflow')


def test_multiturn_budget_keeps_constraints_and_comparison_uses_visible_skus(flow):
    async def run():
        flow.patches.append({'category':'蓝牙耳机'})
        await flow.turn('推荐300元以内蓝牙耳机，不要二手')
        updated=await flow.turn('预算改为500元以内，其他条件不变')
        assert {p['productId'] for p in cards(updated)}=={'p1','p2','p3'}
        requirement=flow.memory['workflow-user'].state['shoppingRequirement']
        assert requirement['hard_attributes'][0]['value']=='全新'
        compared=await flow.turn('第一款和第三款哪款适合游戏？')
        selected=flow.tool_calls[-1][1]['productIds']
        assert len(selected)==2 and [p['productId'] for p in cards(compared)]==selected
        assert all(p['verified']['skuHash']==flow.tool_calls[-1][1]['skuHashes'][p['productId']] for p in cards(compared))
    asyncio.run(run())


@pytest.mark.parametrize('fault,expected_status,code',[
    ('empty','empty','NO_CANDIDATES'),('java_timeout','error','JAVA_TIMEOUT'),
    ('mcp_timeout','transport_error','MCP_TIMEOUT')])
def test_failure_is_classified_and_previous_cards_cannot_leak(flow,fault,expected_status,code):
    async def run():
        flow.patches.append({'category':'蓝牙耳机'})
        assert cards(await flow.turn('推荐300元以内蓝牙耳机'))
        flow.fault=fault
        failed=await flow.turn('预算改为200元以内')
        assert not cards(failed)
        assert flow.memory['workflow-user'].state['shoppingResults']==[]
        assert flow.results[-1].status==expected_status and flow.results[-1].error_code==code
    asyncio.run(run())


def test_bad_model_output_does_not_dispatch_a_business_tool(flow):
    flow.patches.append({'invented_product_id':'fake'})
    result=asyncio.run(flow.turn('推荐蓝牙耳机'))
    assert not cards(result) and not flow.tool_calls and not flow.java_calls


def test_after_sale_policy_then_proposal_remains_read_only_until_confirmation(flow,monkeypatch):
    from app.services.pending_action_service import pending_action_service
    from app.services.redis_service import redis_service
    from app.services.action_execute_service import ActionExecuteService
    async def run():
        policy=await flow.turn('七天退货运费谁承担？',intent='CHAT')
        assert policy['after_sale_handled'] and '不能据此承诺' in ''.join(policy['chunks'])
        proposal=await flow.turn('申请退货退款 20261124000000001DEMO0001_1，原因：质量问题',intent='CHAT')
        assert len(flow.proposals)==1 and '期限尚未核实' in ''.join(proposal['chunks'])
        assert all(not path.endswith('/create') for path,_ in flow.java_calls)
        token=next(iter(flow.proposals));row=flow.proposals[token]
        monkeypatch.setattr(pending_action_service,'get_by_token',AsyncMock(side_effect=lambda _token:copy.deepcopy(row)))
        monkeypatch.setattr(redis_service,'try_lock_pending_action',AsyncMock(return_value='owner'))
        monkeypatch.setattr(redis_service,'clear_pending_executing',AsyncMock())
        monkeypatch.setattr(redis_service,'unlock_pending_action',AsyncMock())
        async def cas(_token,expected,target,**kw):
            if row['status']!=expected: return False
            row.update(kw.get('fields') or {});row['status']=target;return True
        monkeypatch.setattr(redis_service,'cas_pending_status',cas)
        writes=[]
        async def java_write(_token,path,payload):
            assert path=='create' and 'userId' not in payload
            writes.append(copy.deepcopy(payload))
            return {'status':'success','data':{**payload,'status':'PENDING_REVIEW'}}
        executor=ActionExecuteService()
        monkeypatch.setattr(executor,'_after_sale_call',java_write)
        async def execute(pending): return await executor.execute(pending,'controlled-session-token')
        with pytest.raises(ValueError): await pending_action_service.confirm('another-user',token,execute)
        assert not writes
        assert (await pending_action_service.confirm('workflow-user',token,execute)).success
        assert (await pending_action_service.confirm('workflow-user',token,execute)).success
        assert len(writes)==1 and writes[0]['requestId']==row['requestId']
    asyncio.run(run())


def test_forged_tool_user_is_replaced_by_authenticated_user(flow):
    from app.services.mcp_tool_router import mcp_tool_router
    asyncio.run(mcp_tool_router.invoke('CHECK_AFTER_SALE_ELIGIBILITY',{
        'userId':'forged-user','orderItemId':'20261124000000001DEMO0001_1','type':'return_refund'},'workflow-user'))
    assert flow.tool_calls[-1][1]['userId']=='workflow-user'
