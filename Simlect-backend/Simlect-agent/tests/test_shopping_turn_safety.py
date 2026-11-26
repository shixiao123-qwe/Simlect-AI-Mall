import asyncio
import json
from unittest.mock import AsyncMock, Mock, patch
import pytest
from app.models.shopping import ShoppingRequirement
from app.memory.models import SessionMemory
from app.memory.session_memory_service import session_memory_service
from app.services import shopping_turn as turn
from app.services.redis_service import redis_service
from app.services.mcp_tool_router import mcp_tool_router, McpToolRouter
from app.services.tool_invoke_result import ToolInvokeResult
from app.rag.rrf import rrf_merge

@pytest.fixture
def memory(monkeypatch):
    mem=SessionMemory("u1");mem.state.update(shoppingRequirement={"category":"耳机","max_price":"500"},shoppingResults=["p1","p2"],shoppingShownSkus={"p1":"s1","p2":"s2"})
    monkeypatch.setattr(redis_service,"_client",Mock())
    monkeypatch.setattr(session_memory_service,"load",AsyncMock(return_value=mem))
    monkeypatch.setattr(session_memory_service,"save",AsyncMock())
    monkeypatch.setattr(mcp_tool_router,"invoke",AsyncMock(return_value=ToolInvokeResult(content="empty")))
    monkeypatch.setattr(turn,"parse_shopping",AsyncMock(return_value=(ShoppingRequirement(category="耳机",max_price="200"),None)))
    return mem

def state(text):return {"user_id":"u1","user_text":text,"intent":"CHAT"}

@pytest.mark.asyncio
async def test_budget_change_invalidates_old_results_before_search(memory):
    async def call(tool,args,user):
        assert memory.state["shoppingResults"]==[]
        assert tool=="RECOMMEND_PRODUCTS" and args["requirements"]["max_price"]=="200"
        return ToolInvokeResult(content="empty")
    mcp_tool_router.invoke.side_effect=call
    await turn.shopping_turn(state("预算改成200"))
    mcp_tool_router.invoke.assert_awaited_once()
    assert memory.state["shoppingShownSkus"]=={}

@pytest.mark.asyncio
@pytest.mark.parametrize("error",["CLARIFY:请明确品类","ValidationError"])
async def test_parse_failure_does_not_fall_back_to_unverified_search(memory,error):
    turn.parse_shopping.return_value=(None,error)
    result=await turn.shopping_turn(state("预算改成200"))
    assert result.content and not result.product_ids
    assert memory.state["shoppingResults"]==[]
    mcp_tool_router.invoke.assert_not_awaited()

@pytest.mark.asyncio
async def test_mcp_failure_does_not_retain_old_cards(memory):
    mcp_tool_router.invoke.side_effect=TimeoutError()
    result=await turn.shopping_turn(state("预算改成200"))
    assert not result.product_ids and memory.state["shoppingResults"]==[]
    mcp_tool_router.invoke.assert_awaited_once()

@pytest.mark.asyncio
async def test_turn_timeout_never_reuses_old_results(memory,monkeypatch):
    monkeypatch.setattr(turn,"SHOPPING_TURN_TIMEOUT",.01)
    async def slow(*args):await asyncio.sleep(1)
    turn.parse_shopping.side_effect=slow
    result=await turn.shopping_turn(state("预算改成200"))
    assert not result.product_ids and memory.state["shoppingResults"]==[]
    mcp_tool_router.invoke.assert_not_awaited()

@pytest.mark.asyncio
async def test_compare_uses_visible_pairs(memory):
    await turn.shopping_turn(state("比较第1款和第2款"))
    args=mcp_tool_router.invoke.await_args.args
    assert args[0]=="COMPARE_PRODUCTS" and args[1]["skuHashes"]=={"p1":"s1","p2":"s2"}
    assert args[1]["requirements"]["max_price"]=="500"
    turn.parse_shopping.assert_not_awaited()

@pytest.mark.asyncio
async def test_budget_change_precedes_compare(memory):
    await turn.shopping_turn(state("预算改成200，对比第1款和第2款"))
    assert mcp_tool_router.invoke.await_args.args[0]=="RECOMMEND_PRODUCTS"

@pytest.mark.asyncio
@pytest.mark.parametrize("text",["比较这两款","比较第1款和第9款","比较第0款和第1款"])
async def test_comparison_does_not_guess_references(memory,text):
    result=await turn.shopping_turn(state(text))
    assert not result.product_ids
    mcp_tool_router.invoke.assert_not_awaited()

@pytest.mark.asyncio
async def test_router_rejects_unseen_and_overrides_model_sku(memory):
    router=McpToolRouter()
    with patch("app.services.mcp_tool_router.mcp_streamable_client.call_tool",new_callable=AsyncMock) as call:
        call.return_value="verified"
        result=await router.invoke("COMPARE_PRODUCTS",{"productIds":["unseen"]},"u1")
        assert not result.product_ids;call.assert_not_awaited()
        await router.invoke("COMPARE_PRODUCTS",{"productIds":["p1","p2"],"skuHashes":{"p1":"forged"},"userId":"forged"},"u1")
        args=call.await_args.args[1]
        assert args["userId"]=="u1" and args["skuHashes"]["p1"]=="s1"
        assert args["requirements"]["max_price"]=="500"

def test_rrf_duplicate_ids_do_not_inflate_rank():
    assert rrf_merge(["p1"]*50+["p2"],["p2"],2)[0]=="p2"
    assert rrf_merge(["p1"],[],0)==[]

@pytest.mark.asyncio
async def test_embedding_exception_is_contained(monkeypatch):
    from app.rag import retriever
    rag=retriever.RagRetriever();breaker=Mock();breaker.allow_request.return_value=True
    monkeypatch.setattr(retriever.circuit_registry,"get_or_create",Mock(return_value=breaker))
    with patch("app.rag.retriever.embed_text",new_callable=AsyncMock) as embed:
        embed.side_effect=RuntimeError("embedding down")
        assert await rag.search_product_vector_ids("耳机",50)==[]
        breaker.record_failure.assert_called_once()

@pytest.mark.asyncio
async def test_keyword_circuit_open_uses_one_existing_fallback(monkeypatch):
    from app.rag import retriever
    rag=retriever.RagRetriever();breaker=Mock();breaker.allow_request.return_value=False
    monkeypatch.setattr(retriever.circuit_registry,"get_or_create",Mock(return_value=breaker))
    with patch.object(rag,"_product_search_fallback",new_callable=AsyncMock) as fallback:
        fallback.return_value=["p1"]
        assert await rag.search_product_keyword_ids("耳机",50)==["p1"]
        fallback.assert_awaited_once_with("耳机",50)

@pytest.mark.asyncio
async def test_keyword_es_timeout_falls_back_once(monkeypatch):
    import httpx
    from app.rag import retriever
    rag=retriever.RagRetriever();breaker=Mock();breaker.allow_request.return_value=True
    monkeypatch.setattr(retriever.circuit_registry,"get_or_create",Mock(return_value=breaker))
    original=httpx.AsyncClient
    def outage(request):raise httpx.ReadTimeout("ES timeout",request=request)
    with patch("app.rag.retriever.httpx.AsyncClient",side_effect=lambda **kw:original(transport=httpx.MockTransport(outage),**kw)), patch.object(rag,"_product_search_fallback",new_callable=AsyncMock) as fallback:
        fallback.return_value=["p1"]
        assert await rag.search_product_keyword_ids("耳机",50)==["p1"]
        fallback.assert_awaited_once()

@pytest.mark.asyncio
async def test_unbound_comparison_never_fetches_products():
    from app.services import shopping_service
    with patch.object(shopping_service,"verified_rows",new_callable=AsyncMock) as verify:
        result=await shopping_service.compare(["p1","p2"],[])
        assert not result.product_ids;verify.assert_not_awaited()
