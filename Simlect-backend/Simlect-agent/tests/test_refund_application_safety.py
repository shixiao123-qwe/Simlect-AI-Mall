"""T01: legacy tool compatibility, confirmed applications and repeat confirmation."""
import json
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.services import after_sale_service, mcp_tools_service
from app.services.action_execute_service import ActionExecuteService, JavaBridge
from app.services.pending_action_service import PendingActionService
from app.services.tool_invoke_result import parse_tool_wire


@pytest.mark.asyncio
async def test_legacy_refund_card_cannot_call_old_endpoint():
    service = ActionExecuteService()
    with patch.object(service._bridge, "refund_order", new_callable=AsyncMock) as refund:
        with pytest.raises(ValueError, match="旧退款确认卡已停用"):
            await service.execute({"actionType": "REFUND", "paramsJson": json.dumps({"orderItemId": "o1_1"})}, "token")
        refund.assert_not_awaited()
    bridge = JavaBridge()
    with patch.object(bridge, "_post_form", new_callable=AsyncMock) as post:
        with pytest.raises(ValueError, match="售后申请"):
            await bridge.refund_order("token", "o1_1")
        post.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("request_type", ["refund", "return_refund"])
async def test_refund_tool_creates_existing_after_sale_proposal(request_type):
    with patch.object(after_sale_service, "eligibility", new_callable=AsyncMock) as check:
        check.return_value = {"eligible": True, "orderId": "o1", "returnWindowStatus": "POLICY_MISSING"}
        with patch.object(after_sale_service.pending_action_service, "create_pending", new_callable=AsyncMock) as pending:
            pending.return_value = {"token": "act_test"}
            wire = await mcp_tools_service.propose_refund("u1", "o1_1", request_type, "商品有问题")
            check.assert_awaited_once_with("u1", "o1_1", request_type)
            args = pending.await_args.args
            assert args[0:2] == ("AFTER_SALE_REQUEST", "u1")
            assert args[2]["type"] == request_type
            assert args[2]["reason"] == "商品有问题"
            assert len(args[2]["requestId"]) == 32
            assert "refundAmount" not in args[2]
            assert "尚未提交" in parse_tool_wire(wire).content


@pytest.mark.asyncio
async def test_missing_reason_does_not_invent_application():
    with patch.object(after_sale_service, "propose", new_callable=AsyncMock) as propose:
        result = await mcp_tools_service.propose_refund("u1", "o1_1")
        assert "原因" in result
        propose.assert_not_awaited()


@pytest.mark.asyncio
async def test_duplicate_agent_confirmation_posts_one_authenticated_application():
    request_id = "a" * 32
    pending = {"token": "act_test", "userId": "u1", "status": 0,
               "actionType": "AFTER_SALE_REQUEST", "paramsJson": json.dumps({
                   "requestId": request_id, "orderItemId": "o1_1", "type": "return_refund",
                   "reason": "商品破损", "userId": "forged", "refundAmount": 999})}
    requests = []

    def handle(request):
        requests.append(request)
        assert request.url.path == "/api/order/afterSale/create"
        assert request.headers["token"] == "session-token"
        assert "token=session-token" in request.headers["cookie"]
        assert json.loads(request.content) == {"requestId": request_id, "orderItemId": "o1_1",
                                               "type": "return_refund", "reason": "商品破损"}
        return httpx.Response(200, json={"status": "success", "data": {
            "requestId": request_id, "orderItemId": "o1_1", "type": "return_refund", "status": "PENDING_REVIEW"}})

    actual_client = httpx.AsyncClient
    def client(**kwargs):
        return actual_client(transport=httpx.MockTransport(handle), **kwargs)

    redis = AsyncMock()
    redis.try_lock_pending_action.return_value = "owner"
    redis.try_mark_pending_executing.return_value = True
    async def cas(token, expected, target, **kwargs):
        if pending["status"] != expected:
            return False
        pending["status"] = target
        pending.update(kwargs.get("fields") or {})
        return True
    redis.cas_pending_status.side_effect = cas
    service = PendingActionService()
    executor = ActionExecuteService()
    with patch("app.services.pending_action_service.redis_service", redis), \
         patch.object(service, "get_by_token", AsyncMock(return_value=pending)), \
         patch("app.services.action_execute_service.httpx.AsyncClient", side_effect=client):
        result = await service.confirm("u1", "act_test", lambda row: executor.execute(row, "session-token"))
        assert result[1] is True
        assert "待人工审核" in result[2]
        repeated = await service.confirm("u1", "act_test", lambda row: executor.execute(row, "session-token"))
        assert repeated[1] is True
        assert repeated[2] == result[2]
        with pytest.raises(ValueError, match="无权"):
            await service.confirm("another-user", "act_test", AsyncMock())
    assert len(requests) == 1


@pytest.mark.asyncio
async def test_propose_refund_schema_and_router_keep_type_reason_and_session_identity():
    from app.mcp.tools import build_mcp_tools
    from app.services.mcp_tool_router import McpToolRouter
    tool = next(tool for tool in build_mcp_tools() if tool.name == "PROPOSE_REFUND")
    args = {"userId": "forged", "orderItemId": "o1_1", "type": "return_refund", "reason": "破损"}
    with patch("app.services.mcp_tool_router.mcp_streamable_client.call_tool", new_callable=AsyncMock) as call:
        call.return_value = "待确认申请"
        await McpToolRouter().invoke(tool.name, args, "u1")
        call.assert_awaited_once_with("PROPOSE_REFUND", {**args, "userId": "u1"})
    assert {"type", "reason"}.issubset(tool.args_schema.model_fields)
