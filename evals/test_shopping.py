"""Offline contract evaluation. All LLM/Java/MCP boundaries are mocked explicitly."""
import asyncio
import os
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock
import pytest
from pydantic import ValidationError
os.environ.setdefault('SIMLECT_INTERNAL_TOKEN','offline-eval-token-no-production-access')
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'Simlect-backend/Simlect-agent'))
from app.models.shopping import ShoppingRequirement, ShoppingPatch, merge_requirement
from app.services.shopping_parser import parse_shopping
from app.services import shopping_parser, shopping_service
from app.services.shopping_turn import comparison_indices
from app.services.tool_invoke_result import ToolInvokeResult

@pytest.mark.parametrize('value', ['0','0.01','299.99','300','99999999.99'])
def test_budget_accepts_valid_decimal(value):
    assert str(ShoppingRequirement(max_price=value).max_price)==value

@pytest.mark.parametrize('data', [dict(max_price=-1),dict(min_price=301,max_price=300),dict(max_price='abc'),
    dict(max_price='0.001'),dict(max_price='NaN'),dict(max_price='Infinity'),dict(category='x'*81),
    dict(product_id='invented'),dict(preferences=['x'*81]),dict(hard_attributes=[dict(name='',value='new')])])
def test_invalid_fields_rejected(data):
    with pytest.raises(ValidationError): ShoppingRequirement.model_validate(data)

@pytest.mark.parametrize('patch,expected', [({'max_price':200},'200'),({'max_price':500},'500'),
    ({'clear':['max_price']},None),({'reset':True,'category':'手机'},None)])
def test_multiturn_budget(patch,expected):
    result=merge_requirement({'category':'耳机','max_price':300},ShoppingPatch(**patch))
    assert (str(result.max_price) if result.max_price is not None else None)==expected

def test_multiturn_preferences_and_hard_constraints_preserved():
    result=merge_requirement({'category':'耳机','hard_attributes':[{'name':'成色','value':'全新'}]},ShoppingPatch(preferences=['低延迟']))
    assert result.hard_attributes[0].value=='全新' and result.preferences==['低延迟']

@pytest.mark.parametrize('value', ['bad json',{'max_price':-1},{'unknown':'injection'},TimeoutError(),ValueError()])
def test_bad_model_output_does_not_change_previous(monkeypatch,value):
    fake=MagicMock(); fake.with_structured_output.return_value.ainvoke=AsyncMock(
        side_effect=value if isinstance(value,Exception) else None,return_value=value)
    monkeypatch.setattr(shopping_parser,'create_memory_llm',lambda:fake)
    previous={'category':'耳机','max_price':300}
    result,error=asyncio.run(parse_shopping('预算改为200',previous))
    assert result is None and error and previous['max_price']==300

@pytest.mark.parametrize('text,expected', [('第一款和第三款哪款适合游戏',[1,3]),('第1个与第2个比较',[1,2]),
    ('第一款第二款第三款对比',[1,2,3]),('第一款第一款比较',[1]),('我想买耳机',[])])
def test_compare_references(text,expected): assert comparison_indices(text)==expected

@pytest.mark.parametrize('ids', [[],['p'],['p','p'],['a','b','c','d']])
def test_invalid_compare_selection(ids):
    result=asyncio.run(shopping_service.compare(ids,[]))
    assert '2至3' in result.content

def test_rank_uses_only_real_attributes_and_deduplicates():
    rows=[{'product_id':'p','sku_hash':'a','price':100,'stock':2,'attributes':{}},
          {'product_id':'p','sku_hash':'b','price':120,'stock':2,'attributes':{'特点':'低延迟'}},
          {'product_id':'q','sku_hash':'c','price':90,'stock':2,'attributes':{}}]
    result=shopping_service.rank_rows(rows,ShoppingRequirement(preferences=['低延迟']))
    assert [r['product_id'] for r in result]==['p','q']
    assert result[0]['sku_hash']=='b' and '暂无' in result[1]['reason']

@pytest.mark.parametrize('error', [TimeoutError(),ValueError(),ConnectionError()])
def test_java_failures_return_no_unverified_cards(monkeypatch,error):
    verify=AsyncMock(side_effect=error)
    monkeypatch.setattr(shopping_service,'verified_rows',verify)
    result=asyncio.run(shopping_service.compare(['p','q'],[],sku_hashes={'p':'sp','q':'sq'}))
    assert result.status=='error' and not result.assistant_cards
    assert result.error_code==('JAVA_TIMEOUT' if isinstance(error,TimeoutError) else 'JAVA_UNAVAILABLE')
    verify.assert_awaited_once()

def test_missing_product_and_missing_parameters(monkeypatch):
    monkeypatch.setattr(shopping_service,'verified_rows',AsyncMock(return_value=[
        {'product_id':'p','product_name':'耳机','sku_hash':'a','price':100,'stock':2,'attributes':{}}]))
    result=asyncio.run(shopping_service.compare(['p','missing'],[],sku_hashes={'p':'a','missing':'b'}))
    assert '暂无数据' in result.content and result.product_ids==['p']
    import json
    cards=json.loads(result.assistant_cards)
    assert cards[0]['shopping']['unavailableCount']==1 and cards[0]['verified']['skuHash']=='a'

def test_missing_category_does_not_query_any_backend(monkeypatch):
    backend=AsyncMock(); monkeypatch.setattr(shopping_service,'verified_rows',backend)
    result=asyncio.run(shopping_service.recommend({}))
    assert '品类' in result.content; backend.assert_not_called()

@pytest.mark.parametrize('rows', [[],[{'product_id':'p','product_name':'耳机','sku_hash':'a','price':199,'stock':1,'attributes':{'成色':'全新'}}]])
def test_recommendation_uses_verified_backend_results(monkeypatch,rows):
    monkeypatch.setattr(shopping_service.rag_retriever,'search_product_keyword_ids',AsyncMock(return_value=['p']))
    monkeypatch.setattr(shopping_service.rag_retriever,'search_product_vector_ids',AsyncMock(return_value=[]))
    monkeypatch.setattr(shopping_service,'verified_rows',AsyncMock(return_value=rows))
    result=asyncio.run(shopping_service.recommend({'category':'耳机','max_price':300}))
    assert bool(result.assistant_cards)==bool(rows)

def test_mcp_timeout_is_caught_by_router(monkeypatch):
    from app.services import mcp_tool_router as router
    monkeypatch.setattr(router.mcp_streamable_client,'call_tool',AsyncMock(side_effect=TimeoutError()))
    result=asyncio.run(router.mcp_tool_router.invoke('RECOMMEND_PRODUCTS',{'requirements':{'category':'耳机'}},'u'))
    assert '操作失败' in result.content and not result.assistant_cards

def test_patch_null_lists_do_not_erase_previous_preferences():
    result=merge_requirement({'preferences':['低延迟'],'hard_attributes':[{'name':'成色','value':'全新'}]},
                             ShoppingPatch(preferences=None,hard_attributes=None,max_price=200))
    assert result.preferences==['低延迟'] and result.hard_attributes[0].value=='全新'

@pytest.mark.parametrize('intent', ['QUERY_ORDER','REFUND','QUERY_COUPON'])
def test_order_intent_not_hijacked_by_shopping(monkeypatch,intent):
    from app.services.shopping_turn import shopping_turn
    from app.memory.session_memory_service import session_memory_service
    from app.services.redis_service import redis_service
    memory=MagicMock(); memory.state={'shoppingRequirement':{'category':'耳机'}}
    monkeypatch.setattr(session_memory_service,'load',AsyncMock(return_value=memory))
    monkeypatch.setattr(redis_service,'_client',MagicMock())
    result=asyncio.run(shopping_turn({'user_id':'u','intent':intent,'user_text':'预算300的订单退款'}))
    assert result is None

def test_graph_shopping_path_calls_mcp_and_reuses_memory(monkeypatch):
    from app.services import shopping_turn as turn
    from app.memory.session_memory_service import session_memory_service
    from app.services.redis_service import redis_service
    from app.services.mcp_tool_router import mcp_tool_router
    memory=MagicMock();memory.state={'shoppingRequirement':{'category':'耳机','max_price':'300'}}
    monkeypatch.setattr(session_memory_service,'load',AsyncMock(return_value=memory))
    monkeypatch.setattr(session_memory_service,'save',AsyncMock())
    monkeypatch.setattr(redis_service,'_client',MagicMock())
    parser=AsyncMock(return_value=(ShoppingRequirement(category='耳机',max_price=200),None))
    monkeypatch.setattr(turn,'parse_shopping',parser)
    call=AsyncMock(return_value=ToolInvokeResult(content='ok',product_ids=['p'],
        assistant_cards='[{"productId":"p","verified":{"skuHash":"s"}}]'))
    monkeypatch.setattr(mcp_tool_router,'invoke',call)
    asyncio.run(turn.shopping_turn({'user_id':'u','intent':'PRODUCT_SEARCH','user_text':'预算改为200'}))
    assert call.call_args.args[0]=='RECOMMEND_PRODUCTS'
    assert memory.state['shoppingResults']==['p']
    assert memory.state['shoppingShownSkus']=={'p':'s'}
    assert memory.state['shoppingRequirement']['max_price']=='200'

def test_strict_finalize_never_backfills_hot_products(monkeypatch):
    from app.services import agent_runtime as runtime
    monkeypatch.setattr(runtime.stream_service,'push_done',AsyncMock())
    monkeypatch.setattr(runtime.agent_message_service,'complete_message',AsyncMock())
    fallback=AsyncMock();monkeypatch.setattr(runtime,'_ensure_product_search_cards',fallback)
    asyncio.run(runtime.finalize_agent_response({'userId':'u','messageId':1},['无符合预算商品'],[],shopping_handled=True))
    fallback.assert_not_called()

def test_comparison_readable_and_does_not_expose_internal_ids(monkeypatch):
    rows=[{'product_id':'p','product_name':'耳机A','sku_hash':'secret-hash-a','price':199,'stock':10,'attributes':{'成色':'全新','特点':'舒适佩戴'}},
          {'product_id':'q','product_name':'耳机F','sku_hash':'secret-hash-f','price':299,'stock':8,'attributes':{'成色':'全新','特点':'舒适佩戴'}}]
    monkeypatch.setattr(shopping_service,'verified_rows',AsyncMock(return_value=rows))
    result=asyncio.run(shopping_service.compare(['p','q'],['游戏'],
        sku_hashes={'p':'secret-hash-a','q':'secret-hash-f'}))
    assert '不足以判断哪款更适合游戏' in result.content
    assert '便宜100.00元' in result.content and '成色：全新' in result.content
    assert 'secret-hash' not in result.content and 'Java' not in result.content and '{"' not in result.content
    import json
    assert [r['productId'] for r in json.loads(result.assistant_cards)]==['p','q']

def test_gaming_conclusion_is_bound_to_low_latency_attribute():
    rows=[{'product_name':'游戏款','price':399,'attributes':{'特点':'低延迟 游戏'}}]
    text=shopping_service.comparison_conclusion(rows,['游戏'])
    assert '优先考虑游戏款' in text and '实测表现暂无数据' in text

def test_gaming_negated_evidence_is_not_a_recommendation():
    rows=[{'product_name':'普通款','price':199,'attributes':{'特点':'不支持低延迟'}}]
    assert '不足以判断' in shopping_service.comparison_conclusion(rows,['游戏'])

def test_price_format_preserves_cents():
    assert shopping_service.price_text('199')=='199.00'
    assert shopping_service.price_text('0.01')=='0.01'

@pytest.mark.parametrize('text',['【工具调用超时】timeout','【工具调用失败】offline','【工具执行失败】error'])
def test_mcp_returned_failure_does_not_trigger_backfill(monkeypatch,text):
    from app.services import mcp_tool_router as router
    monkeypatch.setattr(router.mcp_streamable_client,'call_tool',AsyncMock(return_value=text))
    backfill=AsyncMock();monkeypatch.setattr(router.mcp_tool_router,'_backfill_structured',backfill)
    result=asyncio.run(router.mcp_tool_router.invoke('SEARCH_PRODUCTS',{'keyword':'耳机'},'u'))
    assert result.content==text
    backfill.assert_not_called()

def test_negated_soft_preference_is_not_ranked_as_positive_evidence():
    row={'attributes':{'特点':'不支持低延迟'}}
    assert shopping_service.preference_matches(row,['低延迟'])==[]
