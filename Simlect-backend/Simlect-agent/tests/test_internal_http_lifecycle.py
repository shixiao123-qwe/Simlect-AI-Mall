"""Real HTTPX MockTransport exercises; no Java/Redis/model calls or real credentials."""
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
import httpx
import pytest

from app.services import internal_http
from app.services import java_internal_client as java
from app.services.request_context import (
    RequestTraceMiddleware, get_trace_id, normalize_trace_id, trace_scope,
)


def clients(monkeypatch, handler):
    actual = httpx.AsyncClient
    created = []
    settings = SimpleNamespace(java_web_url="http://java.test", internal_token="offline-internal-token")
    monkeypatch.setattr(java, "get_settings", lambda: settings)
    def create(**kwargs):
        assert kwargs["limits"].max_connections == 12
        assert kwargs["limits"].max_keepalive_connections == 6
        value = actual(transport=httpx.MockTransport(handler), **kwargs)
        created.append(value)
        return value
    monkeypatch.setattr(internal_http.httpx, "AsyncClient", create)
    return created


@pytest.mark.asyncio
async def test_lifespan_reuses_pool_and_closes_only_after_outermost_owner(monkeypatch):
    created=clients(monkeypatch, lambda request: httpx.Response(200,json={"status":"success","data":[]}))
    async with internal_http.internal_http_lifespan():
        await java.JavaInternalClient().post_json("/internal/test")
        async with internal_http.internal_http_lifespan():
            await java.JavaInternalClient().post_json("/internal/test")
        assert len(created)==1 and not created[0].is_closed
    assert created[0].is_closed


@pytest.mark.asyncio
async def test_cli_calls_outside_lifespan_close_each_client(monkeypatch):
    created=clients(monkeypatch, lambda request:httpx.Response(200,json={"data":1}))
    await java.JavaInternalClient().post_json("/internal/test")
    await java.JavaInternalClient().post_json("/internal/test")
    assert len(created)==2 and all(client.is_closed for client in created)


@pytest.mark.asyncio
async def test_different_timeout_wrappers_share_pool_but_keep_request_timeouts(monkeypatch):
    observed=[]
    def handler(request):
        observed.append(request.extensions["timeout"])
        return httpx.Response(200,json={"data":True})
    created=clients(monkeypatch,handler)
    async with internal_http.internal_http_lifespan():
        await java.JavaInternalClient(timeout=3).post_json("/internal/test")
        await java.JavaInternalClient(timeout=10).post_json("/internal/test")
    assert len(created)==1
    assert [item["read"] for item in observed]==[3,10]


@pytest.mark.asyncio
async def test_headers_trace_and_response_cookies_do_not_cross_concurrent_requests(monkeypatch):
    seen=[]
    async def handler(request):
        await asyncio.sleep(0)
        seen.append((request.url.path,request.headers.get("x-trace-id"),request.headers.get("cookie"),request.headers.get("authorization")))
        return httpx.Response(200,json={"data":True},headers={"Set-Cookie":"token=must-not-be-replayed; Path=/"})
    created=clients(monkeypatch,handler)
    async def request(number):
        trace=f"{number:032x}"
        with trace_scope(trace):
            await java.JavaInternalClient().post_json(f"/internal/user-{number}",{"userId":str(number)})
            assert get_trace_id()==trace
    async with internal_http.internal_http_lifespan():
        await asyncio.gather(request(1),request(2))
        await request(3)
        assert not list(created[0].cookies.jar)
    assert sorted(seen)==[(f"/internal/user-{n}",f"{n:032x}",None,None) for n in (1,2,3)]


@pytest.mark.asyncio
async def test_pool_shutdown_after_exception_or_cancel(monkeypatch):
    created=clients(monkeypatch,lambda request:httpx.Response(200,json={"data":None}))
    for failure in (RuntimeError("test"),asyncio.CancelledError()):
        with pytest.raises(type(failure)):
            async with internal_http.internal_http_lifespan():
                raise failure
        assert created[-1].is_closed
    assert len(created)==2


@pytest.mark.asyncio
async def test_timeout_does_not_retry_post_or_log_sensitive_values(monkeypatch):
    calls=[];events=[]
    def handler(request):
        calls.append(request)
        raise httpx.ReadTimeout("secret-token hidden in transport error",request=request)
    clients(monkeypatch,handler)
    monkeypatch.setattr(java,"logger",SimpleNamespace(info=lambda name,**fields:events.append((name,fields))))
    with trace_scope("a"*32):
        with pytest.raises(httpx.ReadTimeout):
            await java.JavaInternalClient(timeout=2).post_json("/internal/write",{"private":"secret-body"})
    assert len(calls)==1 and calls[0].method=="POST"
    assert events[0][1]["outcome"]=="timeout"
    assert events[0][1]["trace_id"]=="a"*32
    assert "secret" not in repr(events) and "offline-internal-token" not in repr(events)


@pytest.mark.asyncio
@pytest.mark.parametrize("payload,error",[
    ([],java.JavaInternalResponseError),({},java.JavaInternalResponseError),
    ({"status":"error","info":"业务拒绝"},java.JavaInternalBusinessError),
    ({"status":"success","code":600},java.JavaInternalBusinessError)])
async def test_malformed_or_denied_response_never_looks_successful(monkeypatch,payload,error):
    created=clients(monkeypatch,lambda request:httpx.Response(200,json=payload))
    with pytest.raises(error):
        await java.JavaInternalClient().post_json("/internal/test")
    assert created[0].is_closed


@pytest.mark.asyncio
async def test_asgi_application_lifespan_closes_pool_on_startup_failure(monkeypatch):
    created=clients(monkeypatch,lambda request:httpx.Response(200,json={"data":True}))
    async def failing_app(scope,receive,send):
        async with internal_http.internal_http_client() as client:
            assert not client.is_closed
        raise RuntimeError("startup failed")
    wrapper=internal_http.ManagedInternalHttpASGI(failing_app)
    with pytest.raises(RuntimeError):
        await wrapper({"type":"lifespan"},AsyncMock(),AsyncMock())
    assert len(created)==1 and created[0].is_closed


@pytest.mark.parametrize("value",[None,"", "x"*1000,"request\r\nforged-header", "not-a-trace"])
def test_invalid_trace_is_replaced(value):
    normalized=normalize_trace_id(value)
    assert len(normalized)==32 and int(normalized,16)>=0


def test_trace_scope_restores_parent_and_normalizes_uuid():
    assert normalize_trace_id("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB")=="abcdefabcdefabcdefabcdefabcdefab"
    with trace_scope("a"*32):
        with trace_scope("b"*32): assert get_trace_id()=="b"*32
        assert get_trace_id()=="a"*32
    assert get_trace_id()!="a"*32


@pytest.mark.asyncio
@pytest.mark.parametrize("headers",[[],[(b"x-trace-id",b"c"*32)],[(b"x-trace-id",b"c"*32+b"\xff")],[(b"x-trace-id",b"c"*32),(b"x-trace-id",b"d"*32)]])
async def test_trace_asgi_is_scoped_bounded_and_returned(headers):
    captured=[];sent=[]
    async def app(scope,receive,send):
        captured.append(get_trace_id())
        await send({"type":"http.response.start","status":200,"headers":[]})
        await send({"type":"http.response.body","body":b"ok"})
    async def send(message):sent.append(message)
    await RequestTraceMiddleware(app)({"type":"http","headers":headers},AsyncMock(),send)
    trace=dict(sent[0]["headers"])[b"x-trace-id"].decode()
    assert trace==captured[0] and len(trace)==32
    if len(headers)==1 and headers[0][1]==b"c"*32: assert trace=="c"*32
    else: assert trace!="c"*32
    assert get_trace_id()!=trace


@pytest.mark.asyncio
async def test_fastapi_startup_failure_closes_pool_and_redis(monkeypatch):
    # Actual application's lifecycle, all outside resources replaced with local mocks.
    import app.main as main
    from app.memory.session_memory_service import session_memory_service
    created=clients(monkeypatch,lambda request:httpx.Response(200,json={"data":True}))
    redis=SimpleNamespace(connect=AsyncMock(),close=AsyncMock(),client=Mock())
    monkeypatch.setattr(main,"redis_service",redis)
    monkeypatch.setattr(main,"init_pool",AsyncMock(side_effect=RuntimeError("database unavailable")))
    close=AsyncMock();monkeypatch.setattr(main,"close_pool",close)
    monkeypatch.setattr(session_memory_service,"ensure_table",AsyncMock())
    with pytest.raises(RuntimeError):
        async with main.lifespan(main.app):pass
    redis.close.assert_awaited_once();close.assert_awaited_once()
    assert created[0].is_closed
