"""T04 fault windows; Redis/HTTP are controlled fakes, no live Java or payment calls."""
import asyncio
import copy
import json
import time
from unittest.mock import AsyncMock, patch
import httpx
import pytest
from app.services.pending_action_service import PendingActionService
from app.services.action_execute_service import ActionExecuteService

@pytest.fixture
def context():
    row={"token":"act_one","userId":"u1","actionType":"AFTER_SALE_REQUEST","status":0,
         "requestId":"a"*32,"expiresAt":int(time.time()*1000)+60000,
         "paramsJson":json.dumps({"requestId":"a"*32,"orderItemId":"o1_1","type":"return_refund","reason":"broken","userId":"forged"})}
    redis=AsyncMock();redis.try_lock_pending_action.return_value="owner"
    redis.get_pending_action.side_effect=lambda token:copy.deepcopy(row) if row else None
    async def cas(token,expected,target,**kwargs):
        if row.get("status")!=expected:return False
        row.update(kwargs.get("fields") or {});row["status"]=target;return True
    redis.cas_pending_status.side_effect=cas
    with patch("app.services.pending_action_service.redis_service",redis):
        yield PendingActionService(),row,redis

@pytest.mark.asyncio
async def test_normal_confirmation_retains_result_and_duplicate_never_executes(context):
    service,row,redis=context;execute=AsyncMock(return_value="submitted")
    first=await service.confirm("u1","act_one",execute)
    repeat=await service.confirm("u1","act_one",execute)
    assert first.success and repeat.success and repeat.message=="submitted"
    assert row["status"]==1;execute.assert_awaited_once();redis.delete_pending_action.assert_not_awaited()

@pytest.mark.asyncio
async def test_java_commits_then_response_times_out_and_query_recovers_real_application(context):
    service,row,redis=context;execution=ActionExecuteService();requests=[];committed={}
    def handler(request):
        payload=json.loads(request.content);requests.append((request.url.path,payload))
        assert request.headers["token"]=="session-token"
        assert "token=session-token" in request.headers["cookie"]
        assert "userId" not in payload and payload["requestId"]=="a"*32
        if request.url.path.endswith("/create"):
            committed.update(payload,status="APPROVED")
            raise httpx.ReadTimeout("response lost after commit",request=request)
        assert request.url.path.endswith("/result")
        return httpx.Response(200,json={"status":"success","data":{"state":"SUCCEEDED","application":committed}})
    original=httpx.AsyncClient
    with patch("app.services.action_execute_service.httpx.AsyncClient",side_effect=lambda **kw:original(transport=httpx.MockTransport(handler),**kw)):
        result=await service.confirm("u1","act_one",lambda p:execution.execute(p,"session-token"),lambda p:execution.recover(p,"session-token"))
        repeat=await service.confirm("u1","act_one",lambda p:execution.execute(p,"session-token"))
    assert result.success and repeat.success and row["status"]==1
    assert "APPROVED" in result.message and "不代表退款" in result.message
    assert sum(path.endswith("/create") for path,_ in requests)==1

@pytest.mark.asyncio
async def test_unknown_is_not_failed_and_missing_snapshot_does_not_automatically_retry(context):
    service,row,redis=context;execute=AsyncMock(side_effect=httpx.ReadTimeout("timeout"))
    query=AsyncMock(return_value={"state":"NOT_FOUND","retryAllowed":True})
    result=await service.confirm("u1","act_one",execute,query)
    assert not result.success and result.status==6 and row["status"]==6
    execute.assert_awaited_once()

@pytest.mark.asyncio
async def test_recovery_only_can_offer_same_id_retry_but_never_writes(context):
    service,row,redis=context;row["status"]=6;execute=AsyncMock(return_value="submitted")
    query=AsyncMock(return_value={"state":"NOT_FOUND","retryAllowed":True})
    result=await service.confirm("u1","act_one",execute,query,recover_only=True)
    assert result.status==6 and result.retry_allowed;execute.assert_not_awaited()
    result=await service.confirm("u1","act_one",execute,query)
    assert result.success;assert execute.await_args.args[0]["requestId"]=="a"*32
    assert json.loads(execute.await_args.args[0]["paramsJson"])["requestId"]=="a"*32

@pytest.mark.asyncio
@pytest.mark.parametrize("query_result",[{"state":"UNKNOWN"},{"state":"NOT_FOUND","retryAllowed":False}])
async def test_unresolved_action_cannot_write_again(context,query_result):
    service,row,redis=context;row["status"]=6;execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute,AsyncMock(return_value=query_result))
    assert result.status==6;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_redis_delete_and_unlock_failure_does_not_change_success(context):
    service,row,redis=context;execute=AsyncMock(return_value="submitted")
    redis.clear_pending_executing.side_effect=RuntimeError("delete unavailable")
    redis.unlock_pending_action.side_effect=RuntimeError("unlock unavailable")
    result=await service.confirm("u1","act_one",execute)
    assert result.success and row["status"]==1
    repeat=await service.confirm("u1","act_one",execute)
    assert repeat.success;execute.assert_awaited_once()

@pytest.mark.asyncio
async def test_cache_update_failure_after_java_success_recovers_by_original_id(context):
    service,row,redis=context;execute=AsyncMock(return_value="submitted")
    normal=redis.cas_pending_status.side_effect
    async def fail_final(token,expect,target,**kw):
        if target==1:raise RuntimeError("Redis save unavailable")
        return await normal(token,expect,target,**kw)
    redis.cas_pending_status.side_effect=fail_final
    assert (await service.confirm("u1","act_one",execute)).success
    assert row["status"]==4
    redis.cas_pending_status.side_effect=normal
    result=await service.confirm("u1","act_one",execute,AsyncMock(return_value={"state":"SUCCEEDED","message":"verified"}))
    assert result.success and row["status"]==1;execute.assert_awaited_once()

@pytest.mark.asyncio
async def test_expired_confirmation_never_calls_java(context):
    service,row,redis=context;row["expiresAt"]=1;execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute)
    assert result.status==3 and row["status"]==3;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_other_user_cannot_confirm_cancel_or_query_even_when_locked(context):
    service,row,redis=context;execute=AsyncMock();query=AsyncMock()
    redis.try_lock_pending_action.return_value=None
    with pytest.raises(ValueError,match="无权"):
        await service.confirm("attacker","act_one",execute,query,recover_only=True)
    with pytest.raises(ValueError,match="无权"):
        await service.cancel("attacker","act_one")
    execute.assert_not_awaited();query.assert_not_awaited();redis.try_lock_pending_action.assert_not_awaited()

@pytest.mark.asyncio
async def test_missing_token_never_calls_java(context):
    service,row,redis=context;row.clear();execute=AsyncMock()
    with pytest.raises(ValueError,match="过期"):
        await service.confirm("u1","act_one",execute)
    execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_cancel_is_retained_and_blocks_execute(context):
    service,row,redis=context;execute=AsyncMock()
    await service.cancel("u1","act_one")
    assert row["status"]==2
    result=await service.confirm("u1","act_one",execute)
    assert result.status==2;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_expired_pending_can_still_recover_previously_executing_action(context):
    service,row,redis=context;row.update(status=4,expiresAt=1);execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute,AsyncMock(return_value={"state":"SUCCEEDED","message":"verified"}))
    assert result.success;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_old_preexecution_confirmed_flag_is_not_assumed_succeeded(context):
    service,row,redis=context;row["status"]=1;execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute,AsyncMock(return_value={"state":"UNKNOWN"}))
    assert result.status==6 and not result.success;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_lock_busy_returns_executing_without_another_write(context):
    service,row,redis=context;redis.try_lock_pending_action.return_value=None;execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute)
    assert result.status==4 and not result.success;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_clear_business_rejection_is_failed_and_not_retried(context):
    service,row,redis=context;execute=AsyncMock(side_effect=ValueError("not eligible"))
    result=await service.confirm("u1","act_one",execute)
    assert result.status==5 and row["status"]==5
    await service.confirm("u1","act_one",execute);execute.assert_awaited_once()

@pytest.mark.asyncio
async def test_request_id_is_assigned_once_and_params_do_not_define_identity(context):
    service,row,redis=context
    created=await service.create_pending("AFTER_SALE_REQUEST","u1",{"orderItemId":"o1_1"},"proposal")
    assert len(created["requestId"])==32 and created["userId"]=="u1"
    assert json.loads(created["paramsJson"])["requestId"]==created["requestId"]
    assert created["expiresAt"]>created["createTime"]
    assert redis.save_pending_action.await_args.kwargs["ttl_seconds"]==service.RESULT_TTL

@pytest.mark.asyncio
async def test_request_id_tamper_fails_before_http(context):
    _,row,_=context;row["requestId"]="b"*32;execution=ActionExecuteService()
    with patch.object(execution,"_after_sale_call",new_callable=AsyncMock) as call:
        with pytest.raises(ValueError,match="不一致"):
            await execution.execute(row,"token")
        call.assert_not_awaited()

@pytest.mark.asyncio
async def test_malformed_java_response_is_unknown_not_business_rejection(context):
    service,row,_=context;execution=ActionExecuteService();original=httpx.AsyncClient
    with patch("app.services.action_execute_service.httpx.AsyncClient",side_effect=lambda **kw:original(transport=httpx.MockTransport(lambda r:httpx.Response(200,text="broken response")),**kw)):
        result=await service.confirm("u1","act_one",lambda p:execution.execute(p,"token"))
    assert result.status==6 and row["status"]==6

@pytest.mark.asyncio
async def test_result_for_another_request_cannot_be_reported_as_success(context):
    service,row,_=context;execution=ActionExecuteService()
    with patch.object(execution,"_after_sale_call",new_callable=AsyncMock) as call:
        call.return_value={"status":"success","data":{"state":"SUCCEEDED","application":{"requestId":"b"*32,"orderItemId":"o1_1","type":"return_refund","status":"PENDING_REVIEW"}}}
        result=await service.confirm("u1","act_one",AsyncMock(side_effect=RuntimeError("timeout")),lambda p:execution.recover(p,"token"))
    assert result.status==6 and not result.success

@pytest.mark.asyncio
async def test_simultaneous_clicks_have_one_executor(context):
    service,row,redis=context;started=asyncio.Event();finish=asyncio.Event();held=False
    async def lock(*args,**kwargs):
        nonlocal held
        if held:return None
        held=True;return "owner"
    redis.try_lock_pending_action.side_effect=lock
    async def work(pending):
        started.set();await finish.wait();return "submitted"
    execute=AsyncMock(side_effect=work)
    first=asyncio.create_task(service.confirm("u1","act_one",execute))
    await asyncio.wait_for(started.wait(),1)
    second=await service.confirm("u1","act_one",execute)
    assert second.status==4 and not second.success
    finish.set();assert (await first).success;execute.assert_awaited_once()

@pytest.mark.asyncio
async def test_unknown_receipt_cannot_be_reexecuted_without_a_business_ledger(context):
    service,row,_=context;row.update(status=6,actionType="CONFIRM_RECEIPT");execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute,AsyncMock(return_value={"state":"NOT_FOUND","retryAllowed":True}))
    assert result.status==6;execute.assert_not_awaited()

@pytest.mark.asyncio
async def test_redis_lua_updates_status_and_message_in_one_operation():
    from app.services.redis_service import RedisService
    redis=RedisService();redis._client=AsyncMock();redis._client.eval.return_value=1
    assert await redis.cas_pending_status("act_one",4,1,ttl_seconds=604800,fields={"resultMessage":"submitted"})
    args=redis._client.eval.await_args.args
    assert args[3:6]==("4","1","604800")
    assert json.loads(args[6])=={"resultMessage":"submitted"}
    assert "for field, value in pairs(patch)" in args[0]

@pytest.mark.asyncio
async def test_done_cache_is_available_even_when_unlock_delete_has_failed(context):
    service,row,redis=context;row.update(status=1,resultMessage="verified")
    redis.try_lock_pending_action.return_value=None;execute=AsyncMock()
    result=await service.confirm("u1","act_one",execute)
    assert result.success and result.message=="verified"
    execute.assert_not_awaited();redis.try_lock_pending_action.assert_not_awaited()

@pytest.mark.asyncio
@pytest.mark.parametrize("action,params,method",[
    ("CONFIRM_RECEIPT",{"orderId":"o1"},"confirm_order"),
    ("PRODUCT_REVIEW",{"orderId":"o1","commentContent":"good","star":5},"post_comment"),
    ("RECOMMENT",{"orderId":"o1","reCommentContent":"good"},"post_recomment")])
async def test_existing_other_writes_keep_original_request_id(action,params,method):
    execution=ActionExecuteService();params["requestId"]="a"*32
    with patch.object(execution._bridge,method,new_callable=AsyncMock) as call:
        call.return_value="done"
        await execution.execute({"actionType":action,"requestId":"a"*32,"paramsJson":json.dumps(params)},"session-token")
        assert call.await_args.kwargs["request_id"]=="a"*32
