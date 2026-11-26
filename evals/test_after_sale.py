"""Offline after-sale contracts. No actual refund, DB write, provider or network call."""
import asyncio
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
import pytest
import httpx
os.environ.setdefault('SIMLECT_INTERNAL_TOKEN','offline-after-sale-tests-only')
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'Simlect-backend/Simlect-agent'))
from app.services.after_sale_turn import after_sale_intent, after_sale_turn
from app.services.after_sale_policy import query_policy
from app.services import after_sale_service as service
from app.services.tool_invoke_result import ToolInvokeResult

@pytest.mark.parametrize('text,expected',[
 ('我的订单','orders'),('查询订单详情','orders'),('快递到哪了','logistics'),
 ('退款有什么规则','policy'),('怎么申请退款','policy'),('退货运费谁出','policy'),
 ('可以申请退款吗，原因：坏了','eligibility'),('能否申请退货','eligibility'),
 ('这单能退款吗','eligibility'),('能退货退款吗','eligibility'),('可以换货吗','eligibility'),
 ('我要申请退款','apply'),('帮我退货','apply'),('不要提交退款申请','eligibility'),
 ('推荐耳机300以内',None)])
def test_intent(text,expected): assert after_sale_intent(text)==expected

def test_policy_quotes_version_and_discloses_limits():
    result=query_policy('七天无理由退款政策')
    assert 'demo-v3' in result and 'AS-004' in result and '不能据此承诺' in result

def test_unknown_and_missing_policy(monkeypatch):
    assert '未收录' in query_policy('火星保险')
    import app.services.after_sale_policy as module
    monkeypatch.setattr(module,'POLICY_FILE',Path('does-not-exist.json'))
    assert '暂不可用' in module.query_policy('退款')

@pytest.mark.parametrize('question',['7 天无理由','七天退货','退货运费谁出','退款多久能到账','换货流程'])
def test_unconfigured_terms_never_become_promises(question):
    answer=query_policy(question)
    assert 'demo-v3' in answer and 'AS-004' in answer
    assert '不能据此承诺' in answer

@pytest.mark.parametrize('corpus',[[],{}, {'version':'test','scope':'demo','documents':[]},
    {'version':'test','scope':'demo','documents':[None]},
    {'version':'test','scope':'demo','documents':[{'id':'x','title':'x','content':'x','keywords':None}]}])
def test_invalid_policy_corpus_fails_closed(monkeypatch,tmp_path,corpus):
    import app.services.after_sale_policy as module
    path=tmp_path/'policy.json';path.write_text(json.dumps(corpus),encoding='utf-8')
    monkeypatch.setattr(module,'POLICY_FILE',path)
    assert '暂不可用' in module.query_policy('退款')

def test_java_window_limits_are_shown_without_overriding_decision(monkeypatch):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(return_value={
        'eligible':True,'policyVersion':'demo-v3','returnWindowStatus':'POLICY_MISSING',
        'returnWindowReason':'缺少正式期限资料，需人工核实'}))
    result=asyncio.run(service.check('u','o_1','return_refund'))
    assert '可提交' in result.content and '期限核实' in result.content
    assert '需人工核实' in result.content

def test_proposal_discloses_unverified_window(monkeypatch):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(return_value={
        'eligible':True,'orderId':'o','returnWindowStatus':'RECEIPT_MISSING'}))
    pending=AsyncMock(return_value={'token':'act_test'})
    monkeypatch.setattr(service.pending_action_service,'create_pending',pending)
    result=asyncio.run(service.propose('u','o_1','return_refund','质量问题'))
    assert '期限尚未核实' in result.content
    pending.assert_awaited_once()

@pytest.mark.parametrize('data',[None,{}, {'eligible':'true'}])
def test_malformed_java_response(monkeypatch,data):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(return_value=data))
    with pytest.raises(ValueError): asyncio.run(service.eligibility('u','o_1','refund'))

@pytest.mark.parametrize('failure',[httpx.ReadTimeout('test'),ValueError('denied')])
def test_failure_never_creates_proposal(monkeypatch,failure):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(side_effect=failure))
    pending=AsyncMock();monkeypatch.setattr(service.pending_action_service,'create_pending',pending)
    with pytest.raises(Exception): asyncio.run(service.propose('u','o_1','refund','质量问题'))
    pending.assert_not_awaited()

def test_ineligible_never_creates_proposal(monkeypatch):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(return_value={'eligible':False,'reason':'已有申请'}))
    pending=AsyncMock();monkeypatch.setattr(service.pending_action_service,'create_pending',pending)
    assert '已有申请' in asyncio.run(service.propose('u','o_1','refund','质量问题')).content
    pending.assert_not_awaited()

def test_proposal_is_pending_only(monkeypatch):
    post=AsyncMock(return_value={'eligible':True,'orderId':'o'});monkeypatch.setattr(service.client,'post_json',post)
    pending=AsyncMock(return_value={'token':'act_'+'a'*32});monkeypatch.setattr(service.pending_action_service,'create_pending',pending)
    result=asyncio.run(service.propose('u','o_1','refund','质量问题'))
    args=pending.await_args.args
    assert args[0]=='AFTER_SALE_REQUEST' and args[1]=='u' and len(args[2]['requestId'])==32
    assert '尚未提交' in result.content
    assert all('/create' not in str(c) for c in post.await_args_list)

def test_order_detail_never_fetches_other_user_items(monkeypatch):
    monkeypatch.setattr(service.client,'list_orders',AsyncMock(return_value=[]))
    items=AsyncMock();monkeypatch.setattr(service.client,'list_order_items',items)
    assert '无权' in asyncio.run(service.order_detail('u','other')).content
    items.assert_not_awaited()

def turn_setup(monkeypatch,previous=None):
    from app.services.mcp_tool_router import mcp_tool_router
    from app.services.redis_service import redis_service
    monkeypatch.setattr(redis_service,'_client',MagicMock())
    from app.memory.session_memory_service import session_memory_service
    memory=SimpleNamespace(state={'afterSaleContext':previous or {}})
    monkeypatch.setattr(session_memory_service,'load',AsyncMock(return_value=memory))
    monkeypatch.setattr(session_memory_service,'save',AsyncMock())
    invoke=AsyncMock(return_value=ToolInvokeResult(content='checked'))
    monkeypatch.setattr(mcp_tool_router,'invoke',invoke)
    return invoke,memory

def test_application_requires_reason_then_uses_same_context(monkeypatch):
    invoke,memory=turn_setup(monkeypatch)
    item='202611021234567890ABCDEF_1'
    result=asyncio.run(after_sale_turn({'user_id':'u','user_text':f'申请退款 {item}'}))
    assert '原因' in result.content and invoke.await_args.args[0]=='CHECK_AFTER_SALE_ELIGIBILITY'
    asyncio.run(after_sale_turn({'user_id':'u','user_text':'原因：质量问题'}))
    assert invoke.await_args.args[:2]==('CREATE_AFTER_SALE_REQUEST',{'orderItemId':item,'type':'refund','reason':'质量问题'})

def test_new_order_does_not_inherit_old_item(monkeypatch):
    invoke,_=turn_setup(monkeypatch,{'orderItemId':'old_1','type':'refund'})
    asyncio.run(after_sale_turn({'user_id':'u','user_text':'申请退款 202611021234567890ABCDEF 原因：破损'}))
    assert invoke.await_args.args[0]=='GET_ORDER_DETAIL'

def test_missing_logistics_id_asks_without_tools(monkeypatch):
    invoke,_=turn_setup(monkeypatch)
    assert '订单号' in asyncio.run(after_sale_turn({'user_id':'u','user_text':'物流到哪了'})).content
    invoke.assert_not_awaited()

def test_tool_identity_overridden(monkeypatch):
    from app.services.mcp_tool_router import mcp_tool_router
    from app.services.mcp_streamable_client import mcp_streamable_client
    call=AsyncMock(return_value='可提交')
    monkeypatch.setattr(mcp_streamable_client,'call_tool',call)
    asyncio.run(mcp_tool_router.invoke('CHECK_AFTER_SALE_ELIGIBILITY',{'userId':'other','orderItemId':'o_1','type':'refund'},'u'))
    assert call.await_args.args[1]['userId']=='u'

def test_confirm_dispatch_uses_user_token_and_never_refund_endpoint(monkeypatch):
    from app.services.action_execute_service import action_execute_service
    seen=[]
    def handler(request):
        seen.append(request)
        return httpx.Response(200,json={'status':'success','data':{
            'requestId':'a'*32,'orderItemId':'o_1','type':'refund','status':'PENDING_REVIEW'}})
    real_client=httpx.AsyncClient
    monkeypatch.setattr('app.services.action_execute_service.httpx.AsyncClient',lambda **kw:real_client(transport=httpx.MockTransport(handler),**kw))
    pending={'actionType':'AFTER_SALE_REQUEST','paramsJson':json.dumps({'requestId':'a'*32,'orderItemId':'o_1','type':'refund','reason':'坏了','userId':'forged'})}
    result=asyncio.run(action_execute_service.execute(pending,'user-token'))
    assert '待人工审核' in result and '不代表退款或换货已执行' in result
    assert len(seen)==1
    assert seen[0].url.path=='/api/order/afterSale/create'
    assert seen[0].headers['token']=='user-token' and 'userId' not in json.loads(seen[0].content)

def test_after_sale_finalize_preserves_confirmation_and_no_shopping(monkeypatch):
    from app.services import agent_runtime as rt
    resolve=AsyncMock(return_value=('CONFIRM_CARD','data','action_confirm'))
    monkeypatch.setattr(rt,'resolve_action_confirm',resolve)
    done=AsyncMock();complete=AsyncMock()
    monkeypatch.setattr(rt.stream_service,'push_done',done)
    monkeypatch.setattr(rt.agent_message_service,'complete_message',complete)
    asyncio.run(rt.finalize_agent_response({'userId':'u','messageId':1},['proposal'],[],after_sale_handled=True))
    assert done.await_args.args[2]=='CONFIRM_CARD'
    complete.assert_awaited_once()


def test_graph_routes_after_sale_before_shopping(monkeypatch):
    from app.graph import nodes
    import app.services.after_sale_turn as module
    import app.services.shopping_turn as shopping
    monkeypatch.setattr(module,'after_sale_turn',AsyncMock(return_value=ToolInvokeResult(content='policy evidence')))
    blocked=AsyncMock(side_effect=AssertionError('shopping must not run'))
    monkeypatch.setattr(shopping,'shopping_turn',blocked)
    monkeypatch.setattr(nodes.rt,'is_cancelled',AsyncMock(return_value=False))
    result=asyncio.run(nodes.agent_loop_node({'user_id':'u','message_id':1,'agent_msg':{},'user_text':'退款政策'}))
    assert result['after_sale_handled'] and result['route']=='finalize'
    assert result['chunks']==['policy evidence']
    blocked.assert_not_awaited()


def test_confirmation_card_has_after_sale_details():
    from app.utils.biz_payload import build_action_confirm_payload
    pending={'token':'act_'+'a'*32,'actionType':'AFTER_SALE_REQUEST','summary':'申请',
             'paramsJson':json.dumps({'orderItemId':'o_1','type':'return_refund','reason':'坏了'})}
    payload,_=build_action_confirm_payload(pending)
    card=json.loads(payload)
    assert card['confirmText']=='确认提交申请'
    assert any(row['value']=='退货退款' for row in card['details'])
    assert '不直接退款' in card['riskTip']


def test_missing_or_unowned_item_has_readable_denial(monkeypatch):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(side_effect=ValueError('订单项不存在或无权访问')))
    result=asyncio.run(service.check('u','mistyped_1','refund'))
    assert '暂不可提交' in result.content and '订单项不存在或无权访问' in result.content


def test_business_denial_cannot_create_pending(monkeypatch):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(side_effect=ValueError('订单项不存在或无权访问')))
    pending=AsyncMock();monkeypatch.setattr(service.pending_action_service,'create_pending',pending)
    result=asyncio.run(service.propose('u','mistyped_1','refund','broken'))
    assert '暂不能提交' in result.content
    pending.assert_not_awaited()


def test_unknown_java_error_not_echoed(monkeypatch):
    monkeypatch.setattr(service.client,'post_json',AsyncMock(side_effect=ValueError('database credentials should never be shown')))
    with pytest.raises(ValueError): asyncio.run(service.eligibility('u','o_1','refund'))


@pytest.mark.parametrize('tools,preserved',[
    (['CREATE_AFTER_SALE_REQUEST'],True),(['PROPOSE_REFUND'],True),
    (['CHECK_AFTER_SALE_ELIGIBILITY'],False),(['PROPOSE_UNKNOWN'],False),([],False)])
def test_output_guard_proposal_whitelist(tools,preserved):
    from app.harness.guardrails.output_guard import OutputGuardrail
    text='请核对并点击确认，尚未提交。 【act_'+'a'*32+'】'
    output=OutputGuardrail().validate_no_false_completion(text,tools)
    assert ('act_' in output)==preserved


def test_application_proposal_does_not_allow_refund_completion_claim():
    from app.harness.guardrails.output_guard import OutputGuardrail
    output=OutputGuardrail().validate_no_false_completion('退款已成功',['CREATE_AFTER_SALE_REQUEST'])
    assert '退款已成功' not in output


def test_finalize_node_outputs_real_after_sale_confirm_card(monkeypatch):
    from app.graph import nodes
    from app.services import agent_runtime as rt
    token='act_'+'a'*32
    pending={'token':token,'userId':'u','actionType':'AFTER_SALE_REQUEST','status':0,'summary':'提交审核',
        'paramsJson':json.dumps({'orderItemId':'o_1','type':'refund','reason':'质量问题','requestId':'b'*32})}
    # Exercise the actual guard -> actual resolver -> actual payload -> DONE pipeline.
    monkeypatch.setattr(rt.pending_action_service,'get_by_token',AsyncMock(return_value=pending))
    monkeypatch.setattr(nodes.sensitive_word_service,'replace',AsyncMock(side_effect=lambda x:x))
    monkeypatch.setattr(nodes.redis_service,'clear_bound_message_id',AsyncMock())
    done=AsyncMock();complete=AsyncMock()
    monkeypatch.setattr(rt.stream_service,'push_done',done)
    monkeypatch.setattr(rt.agent_message_service,'complete_message',complete)
    fail=AsyncMock();monkeypatch.setattr(rt,'push_chat_error',fail)
    result=asyncio.run(nodes.finalize_node({'agent_msg':{'userId':'u','messageId':1},'user_id':'u','message_id':1,
        'chunks':['尚未提交，请点击确认。 【'+token+'】'],'tools_called':['CREATE_AFTER_SALE_REQUEST'],
        'after_sale_handled':True,'llm_messages':[]}))
    fail.assert_not_awaited()
    payload=json.loads(done.await_args.args[2])
    assert payload['type']=='ACTION_CONFIRM' and payload['token']==token
    assert payload['actionType']=='AFTER_SALE_REQUEST' and payload['confirmText']=='确认提交申请'
    assert result['route']=='post_turn'
    assert complete.await_args.args[1]==done.await_args.args[2]


@pytest.mark.parametrize('text',['我的售后申请','查看售后进度','退款进度怎么样'])
def test_after_sale_progress_intent(text):
    assert after_sale_intent(text)=='progress'


def test_progress_uses_scoped_http_and_marks_mock_refund(monkeypatch):
    post=AsyncMock(return_value=[{'requestId':'a'*32,'phase':'COMPLETED','productName':'演示商品','simulated':True}])
    monkeypatch.setattr(service.client,'post_json',post)
    result=asyncio.run(service.request_progress('u1'))
    assert '本地模拟退款' in result.content and '/after-sale-requests' in result.content
    assert post.await_args.args[1]=={'userId':'u1'}
