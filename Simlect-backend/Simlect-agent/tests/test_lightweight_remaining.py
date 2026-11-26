import json
import re
from pathlib import Path
from unittest.mock import AsyncMock,Mock,patch
from contextlib import asynccontextmanager
import pytest
from app.services.agent_engine import AgentEngine
from app.services.redis_service import redis_service
from app.services.health_service import HealthService
from app.services import after_sale_turn
from app.memory.models import SessionMemory

@pytest.mark.asyncio
@pytest.mark.parametrize("busy,previous,allowed",[(True,None,False),(False,b"20",False),(False,b"19",True),(False,b"18",True)])
async def test_same_user_turn_guard(monkeypatch,busy,previous,allowed):
    lock=Mock();lock.acquire=AsyncMock(return_value=not busy);lock.release=AsyncMock()
    client=Mock();client.lock=Mock(return_value=lock);client.get=AsyncMock(return_value=previous);client.set=AsyncMock()
    monkeypatch.setattr(redis_service,"_client",client)
    with patch("app.services.agent_engine.run_agent_graph",new_callable=AsyncMock) as graph:
        if allowed:await AgentEngine().assistant_answer({"userId":"u","messageId":19})
        else:
            with pytest.raises(ValueError):await AgentEngine().assistant_answer({"userId":"u","messageId":19})
        assert graph.await_count==int(allowed)
        assert lock.release.await_count==int(not busy)

@pytest.mark.asyncio
async def test_explicit_new_order_clears_old_after_sale_item(monkeypatch):
    from app.memory.session_memory_service import session_memory_service
    from app.services.mcp_tool_router import mcp_tool_router
    from app.services.tool_invoke_result import ToolInvokeResult
    mem=SessionMemory("u");mem.state['afterSaleContext']={"orderItemId":"old_1","type":"exchange","awaitingReason":True}
    monkeypatch.setattr(redis_service,"_client",Mock())
    monkeypatch.setattr(session_memory_service,"load",AsyncMock(return_value=mem))
    monkeypatch.setattr(session_memory_service,"save",AsyncMock())
    monkeypatch.setattr(mcp_tool_router,"invoke",AsyncMock(return_value=ToolInvokeResult(content="new order")))
    monkeypatch.setattr(after_sale_turn,"extract_order_id",lambda text:"new")
    monkeypatch.setattr(after_sale_turn,"extract_order_item_id",lambda text:None)
    await after_sale_turn.after_sale_turn({"user_id":"u","user_text":"我要退款new"})
    assert mem.state['afterSaleContext']=={}
    assert mcp_tool_router.invoke.await_args.args[0]=='GET_ORDER_DETAIL'

@pytest.mark.asyncio
@pytest.mark.parametrize("status",[200,401,404,503])
async def test_unusable_http_dependencies_are_not_healthy(monkeypatch,status):
    import httpx
    import app.services.health_service as mod
    client=Mock();client.ping=AsyncMock();monkeypatch.setattr(redis_service,"_client",client)
    @asynccontextmanager
    async def acquire():yield Mock(execute=AsyncMock())
    monkeypatch.setattr(mod,"acquire",acquire)
    original=httpx.AsyncClient
    with patch("app.services.health_service.httpx.AsyncClient",side_effect=lambda **kw:original(transport=httpx.MockTransport(lambda r:httpx.Response(status,json={"status":"UP"})),**kw)):
        checks=await HealthService().check_all()
    assert checks['java_web']==(status==200) and checks['elasticsearch']==(status==200)
    assert checks['status']==('ok' if status==200 else 'degraded')

def test_policy_versions_and_missing_receipt_contract():
    root=Path(__file__).resolve().parents[3]
    policy=json.loads((root/'Simlect-backend/Simlect-agent/knowledge/after_sale_policy.json').read_text(encoding='utf-8'))
    java=(root/'Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/AfterSaleService.java').read_text(encoding='utf-8')
    assert re.search(r'POLICY_VERSION="([^"]+)"',java)[1]==policy['version']
    assert 'Receipt.unavailable()' in java and 'getOrderTime' not in java
    assert policy['configured_terms']['return_window_days'] is None

@pytest.mark.asyncio
async def test_vector_query_matches_installed_java_index_contract(monkeypatch):
    import httpx
    from app.rag import retriever
    from types import SimpleNamespace
    settings=SimpleNamespace(vector_search_enabled=True,rag_top_k=5,rag_score_threshold=.4,es_index="simlect_vectorstore")
    monkeypatch.setattr(retriever,"get_settings",lambda:settings)
    breaker=Mock();breaker.allow_request.return_value=True
    monkeypatch.setattr(retriever.circuit_registry,"get_or_create",Mock(return_value=breaker))
    monkeypatch.setattr(retriever,"embed_text",AsyncMock(return_value=[.1,.2]))
    original=httpx.AsyncClient
    def handler(request):
        body=json.loads(request.content)
        assert body['knn']['field']=='embedding'
        assert body['knn']['filter']['bool']['must']==[{'term':{'metadata.dataType':'product'}}]
        return httpx.Response(200,json={'hits':{'hits':[{'_score':.9,'_source':{'content':'test','metadata':{'productId':'p1'}}}]}})
    rag=retriever.RagRetriever.__new__(retriever.RagRetriever);rag._es_hosts='http://es-mock.invalid'
    with patch('app.rag.retriever.httpx.AsyncClient',side_effect=lambda **kw:original(transport=httpx.MockTransport(handler),**kw)):
        assert await rag.search_product_vector_ids('test',5)==['p1']
