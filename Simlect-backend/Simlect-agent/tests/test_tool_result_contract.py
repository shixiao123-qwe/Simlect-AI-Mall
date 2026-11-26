"""Offline v2 result contracts and fault injection; no live MCP/ES/Java/model calls."""
import asyncio
import json
import os
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import httpx
import pytest

os.environ.setdefault('SIMLECT_INTERNAL_TOKEN', 'offline-tool-result-contract-tests')
from app.services.tool_invoke_result import ToolInvokeResult, parse_tool_wire, WIRE_PREFIX


def test_v2_roundtrip_preserves_cards_and_business_fields():
    original=ToolInvokeResult(content='已核验',assistant_cards='[{"productId":"p"}]',
        biz_type='product_search',biz_data='{"x":1}',product_ids=['p'],product_names=['耳机'],
        status='success',degraded=True,degradation_codes=['ES_UNAVAILABLE'])
    restored=parse_tool_wire(original.to_wire())
    assert restored == original
    assert json.loads(original.to_wire()[len(WIRE_PREFIX):])['wireVersion']==2


def test_plain_success_still_has_v2_envelope():
    assert parse_tool_wire(ToolInvokeResult(content='ready').to_wire()).status=='success'
    assert ToolInvokeResult(content='ready').to_wire().startswith(WIRE_PREFIX)


@pytest.mark.parametrize('obj',[[],None,{'wireVersion':2,'content':'x','status':'invented'},
    {'wireVersion':2,'content':'x','status':{}},
    {'wireVersion':2,'content':'x','status':'error'},
    {'wireVersion':2,'content':'x','status':'success','retryable':'yes'},
    {'wireVersion':2,'content':'x','status':'success','productIds':'private'},
    {'wireVersion':3,'content':'private','status':'success'}])
def test_malformed_wire_is_not_returned_to_model(obj):
    result=parse_tool_wire(WIRE_PREFIX+json.dumps(obj))
    assert result.status=='error' and result.error_code=='INVALID_TOOL_RESPONSE'
    assert 'private' not in result.content


def test_invalid_json_fails_closed():
    result=parse_tool_wire(WIRE_PREFIX+'{secret-do-not-show')
    assert result.error_code=='INVALID_TOOL_RESPONSE' and 'secret' not in result.content


def test_error_status_cannot_carry_stale_cards_or_ids():
    result=parse_tool_wire(ToolInvokeResult(content='failed',status='error',error_code='JAVA_TIMEOUT',
        assistant_cards='[{"productId":"p"}]',product_ids=['p'],biz_type='product_search').to_wire())
    assert result.status=='error' and not result.assistant_cards and not result.product_ids
    assert result.biz_type is None


def test_v1_and_plain_results_are_explicitly_legacy():
    plain=parse_tool_wire('legacy text')
    assert plain.content=='legacy text' and plain.status=='legacy' and plain.legacy
    old=parse_tool_wire(WIRE_PREFIX+json.dumps({'content':'old','assistantCards':'[]'}))
    assert old.assistant_cards=='[]' and old.status=='legacy' and old.legacy


@pytest.mark.parametrize('text',['【工具执行失败】old','【工具调用超时】old','【工具调用失败】old'])
def test_old_failure_text_remains_compatible_without_success(text):
    result=parse_tool_wire(text)
    assert result.content==text and result.legacy and result.status=='transport_error'


@pytest.fixture
def shopping(monkeypatch):
    from app.services import shopping_service as service
    monkeypatch.setattr(service,'get_settings',lambda:SimpleNamespace(internal_read_backend='http',vector_search_enabled=False))
    monkeypatch.setattr(service.rag_retriever,'search_product_keyword_ids',AsyncMock(return_value=['p']))
    monkeypatch.setattr(service.rag_retriever,'search_product_vector_ids',AsyncMock(return_value=[]))
    monkeypatch.setattr(service,'verified_rows',AsyncMock(return_value=[{
        'product_id':'p','product_name':'耳机','sku_hash':'s','price':'199.00','stock':2,'attributes':{'成色':'全新'}}]))
    return service


def test_disabled_vector_is_not_a_degradation(shopping):
    result=asyncio.run(shopping.recommend({'category':'耳机'}))
    assert result.status=='success' and not result.degraded
    shopping.rag_retriever.search_product_vector_ids.assert_not_awaited()


def test_normal_empty_recall_is_not_backend_failure(shopping):
    shopping.rag_retriever.search_product_keyword_ids.return_value=[]
    result=asyncio.run(shopping.recommend({'category':'耳机'}))
    assert result.status=='empty' and result.error_code=='NO_CANDIDATES'
    shopping.verified_rows.assert_not_awaited()


def test_java_hard_filter_empty_is_not_transport_failure(shopping):
    shopping.verified_rows.return_value=[]
    result=asyncio.run(shopping.recommend({'category':'耳机'}))
    assert result.status=='empty' and result.error_code=='NO_MATCHES' and not result.assistant_cards


def test_all_recall_backends_failed_are_not_empty(shopping):
    shopping.rag_retriever.search_product_keyword_ids.side_effect=ConnectionError('private')
    result=asyncio.run(shopping.recommend({'category':'耳机'}))
    assert result.status=='error' and result.error_code=='ES_UNAVAILABLE' and not result.assistant_cards
    assert 'private' not in result.content


def test_one_recall_route_failure_keeps_only_java_verified_result(shopping,monkeypatch):
    monkeypatch.setattr(shopping,'get_settings',lambda:SimpleNamespace(internal_read_backend='http',vector_search_enabled=True))
    shopping.rag_retriever.search_product_keyword_ids.side_effect=TimeoutError('private')
    shopping.rag_retriever.search_product_vector_ids.return_value=['p']
    result=asyncio.run(shopping.recommend({'category':'耳机'}))
    assert result.status=='success' and result.degraded and result.product_ids==['p']
    assert result.degradation_codes==['RECALL_TIMEOUT']


@pytest.mark.parametrize('failure,code',[
    (httpx.ReadTimeout('private'),'JAVA_TIMEOUT'),
    (httpx.ConnectError('private'),'JAVA_UNAVAILABLE'),
])
def test_java_transport_failures_are_structured(shopping,failure,code):
    shopping.verified_rows.side_effect=failure
    result=asyncio.run(shopping.recommend({'category':'耳机'}))
    assert result.status=='error' and result.error_code==code and not result.assistant_cards
    assert result.retryable and 'private' not in result.content


def test_java_bad_payload_and_business_denial_are_distinct(shopping):
    from app.services.java_internal_client import JavaInternalResponseError,JavaInternalBusinessError
    for failure,code in [(JavaInternalResponseError('private'),'JAVA_INVALID_RESPONSE'),
                         (JavaInternalBusinessError('private'),'JAVA_REJECTED')]:
        shopping.verified_rows.side_effect=failure
        result=asyncio.run(shopping.recommend({'category':'耳机'}))
        assert result.error_code==code and not result.retryable and 'private' not in result.content


def test_invalid_requirements_do_not_query_backends(shopping):
    result=asyncio.run(shopping.recommend({'category':'耳机','max_price':-1}))
    assert result.status=='invalid_args' and result.error_code=='INVALID_ARGUMENTS'
    shopping.rag_retriever.search_product_keyword_ids.assert_not_awaited()


@pytest.mark.parametrize('status,code',[
    ('empty','NO_MATCHES'),('error','JAVA_TIMEOUT'),('transport_error','MCP_TIMEOUT')])
def test_router_does_not_backfill_failed_or_empty_v2(monkeypatch,status,code):
    from app.services import mcp_tool_router as module
    remote=AsyncMock(return_value=ToolInvokeResult(content='safe',status=status,error_code=code).to_wire())
    monkeypatch.setattr(module.mcp_streamable_client,'call_tool',remote)
    backfill=AsyncMock();monkeypatch.setattr(module.mcp_tool_router,'_backfill_structured',backfill)
    counter=Mock();monkeypatch.setattr(module,'TOOL_CALL_TOTAL',counter)
    result=asyncio.run(module.mcp_tool_router.invoke('SEARCH_PRODUCTS',{'keyword':'耳机'},'u'))
    assert result.status==status
    backfill.assert_not_awaited()
    counter.labels.assert_called_once_with(tool='SEARCH_PRODUCTS',status=status)


def test_router_degraded_success_is_not_counted_as_full_success(monkeypatch):
    from app.services import mcp_tool_router as module
    result=ToolInvokeResult(content='核验通过',degraded=True,degradation_codes=['ES_UNAVAILABLE'])
    monkeypatch.setattr(module.mcp_streamable_client,'call_tool',AsyncMock(return_value=result.to_wire()))
    monkeypatch.setattr(module.mcp_tool_router,'_backfill_structured',AsyncMock(return_value=result))
    counter=Mock();monkeypatch.setattr(module,'TOOL_CALL_TOTAL',counter)
    asyncio.run(module.mcp_tool_router.invoke('RECOMMEND_PRODUCTS',{'requirements':{'category':'耳机'}},'u'))
    counter.labels.assert_called_once_with(tool='RECOMMEND_PRODUCTS',status='degraded')


def test_mcp_protocol_error_never_exposes_remote_details(monkeypatch):
    from app.services import mcp_streamable_client as module
    monkeypatch.setattr(module,'get_settings',lambda:SimpleNamespace(mcp_server_url='http://unused',mcp_client_timeout=1))
    client=module.McpStreamableClient('http://unused')
    session=SimpleNamespace(call_tool=AsyncMock(return_value=SimpleNamespace(
        isError=True,content=[SimpleNamespace(text='secret-stack-and-token')])))
    @asynccontextmanager
    async def fake_session():
        yield session
    monkeypatch.setattr(client,'_session',fake_session)
    result=parse_tool_wire(asyncio.run(client.call_tool('SEARCH_PRODUCTS',{})))
    assert result.status=='transport_error' and result.error_code=='MCP_PROTOCOL_ERROR'
    assert 'secret' not in result.content and not result.retryable


@pytest.mark.parametrize('name,retryable',[('SEARCH_PRODUCTS',True),('CREATE_AFTER_SALE_REQUEST',False),('PROPOSE_REFUND',False)])
def test_mcp_timeout_retry_hint_never_replays_proposals(monkeypatch,name,retryable):
    from app.services import mcp_streamable_client as module
    monkeypatch.setattr(module,'get_settings',lambda:SimpleNamespace(mcp_server_url='http://unused',mcp_client_timeout=1))
    client=module.McpStreamableClient('http://unused')
    call=AsyncMock(side_effect=TimeoutError('private'))
    @asynccontextmanager
    async def fake_session():
        yield SimpleNamespace(call_tool=call)
    monkeypatch.setattr(client,'_session',fake_session)
    result=parse_tool_wire(asyncio.run(client.call_tool(name,{})))
    assert result.error_code=='MCP_TIMEOUT' and result.retryable==retryable
    call.assert_awaited_once()


def test_keyword_backend_failure_with_successful_fallback_is_visible_without_changing_old_api(monkeypatch):
    from app.rag import retriever
    rag=retriever.RagRetriever();breaker=Mock();breaker.allow_request.return_value=False
    monkeypatch.setattr(retriever.circuit_registry,'get_or_create',Mock(return_value=breaker))
    monkeypatch.setattr(retriever.java_internal_client,'search_on_sale',AsyncMock(return_value=[{'product_id':'p'}]))
    with retriever.recall_diagnostics() as diagnostic:
        assert asyncio.run(rag.search_product_keyword_ids('耳机',50))==['p']
    assert diagnostic['degraded'] and diagnostic['error_code']=='ES_UNAVAILABLE'
    assert not diagnostic.get('failed',False)


def test_keyword_primary_and_fallback_failures_are_visible(monkeypatch):
    from app.rag import retriever
    rag=retriever.RagRetriever();breaker=Mock();breaker.allow_request.return_value=False
    monkeypatch.setattr(retriever.circuit_registry,'get_or_create',Mock(return_value=breaker))
    monkeypatch.setattr(retriever.java_internal_client,'search_on_sale',AsyncMock(side_effect=ConnectionError('private')))
    with retriever.recall_diagnostics() as diagnostic:
        assert asyncio.run(rag.search_product_keyword_ids('耳机',50))==[]
    assert diagnostic['failed'] and diagnostic['error_code']=='ES_UNAVAILABLE'


def test_recall_diagnostics_reset_after_scope():
    from app.rag.retriever import recall_diagnostics,_record_recall
    with recall_diagnostics() as first:
        _record_recall(failed=True)
    with recall_diagnostics() as second:
        assert not second
    assert first['failed']


def test_server_marks_unclassified_legacy_handlers_explicitly():
    from app.mcp_server.server import _text
    old=parse_tool_wire(_text(ToolInvokeResult(content='old result',assistant_cards='[]')))
    assert old.legacy and old.status=='legacy' and old.assistant_cards=='[]'
    current=parse_tool_wire(_text(ToolInvokeResult(content='new result'),structured=True))
    assert current.status=='success' and not current.legacy
