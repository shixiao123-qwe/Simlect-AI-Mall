"""Existing confirmation scenarios, updated for T04 result retention and explicit states."""

import json
from unittest.mock import AsyncMock, patch

import pytest

from app.services.pending_action_service import PendingActionService


@pytest.fixture
def service():
    return PendingActionService()


def _redis_stub(success_cas: bool = True):
    """构造 redis_service 桩：连接/锁/exec 标记可用，CAS 默认成功。"""
    redis = AsyncMock()
    redis.ensure_connected = AsyncMock()
    redis.try_lock_pending_action.return_value = "owner1"
    redis.unlock_pending_action = AsyncMock()
    redis.try_mark_pending_executing.return_value = True
    redis.cas_pending_status.return_value = success_cas
    redis.delete_pending_action = AsyncMock()
    redis.clear_pending_executing = AsyncMock()
    return redis


@pytest.mark.asyncio
async def test_confirm_success_retains_pending_result(service):
    pending = {
        "token": "act_test",
        "userId": "u1",
        "actionType": "CONFIRM_RECEIPT",
        "paramsJson": json.dumps({"orderId": "o1"}),
        "status": 0,
    }

    async def executor(p):
        assert p["token"] == "act_test"
        return "订单 o1 已确认收货"

    redis = _redis_stub()
    with patch.object(service, "load_owned", AsyncMock(return_value=pending)):
        with patch("app.services.pending_action_service.redis_service", redis):
            action_type, ok, msg = await service.confirm("u1", "act_test", executor)

    assert action_type == "CONFIRM_RECEIPT"
    assert ok is True
    assert "确认收货" in msg
    # EXECUTING precedes Java; success and message are retained for duplicate confirms.
    redis.cas_pending_status.assert_any_await("act_test", 0, 4, ttl_seconds=service.RESULT_TTL)
    redis.cas_pending_status.assert_any_await("act_test", 4, 1, ttl_seconds=service.RESULT_TTL,
                                            fields={"resultMessage": "订单 o1 已确认收货"})
    redis.delete_pending_action.assert_not_awaited()
    redis.clear_pending_executing.assert_awaited_once_with("act_test")


@pytest.mark.asyncio
async def test_confirm_ignores_delete_failure_by_retaining_verified_result(service):
    pending = {"token": "act_del", "userId": "u1", "actionType": "REFUND", "status": 0}

    async def executor(_):
        return "已提交退款"

    redis = _redis_stub()
    redis.delete_pending_action = AsyncMock(side_effect=RuntimeError("redis down"))
    with patch.object(service, "load_owned", AsyncMock(return_value=pending)):
        with patch("app.services.pending_action_service.redis_service", redis):
            action_type, ok, msg = await service.confirm("u1", "act_del", executor)

    assert ok is True
    assert msg == "已提交退款"
    redis.delete_pending_action.assert_not_awaited()
    assert redis.cas_pending_status.await_count == 2


@pytest.mark.asyncio
async def test_confirm_explicit_failure_retains_failed_status(service):
    pending = {
        "token": "act_fail",
        "userId": "u1",
        "actionType": "REFUND",
        "paramsJson": "{}",
        "status": 0,
    }

    async def executor(_):
        raise ValueError("退款失败：订单状态不允许")

    redis = _redis_stub()
    with patch.object(service, "load_owned", AsyncMock(return_value=pending)):
        with patch("app.services.pending_action_service.redis_service", redis):
            action_type, ok, msg = await service.confirm("u1", "act_fail", executor)

    assert action_type == "REFUND"
    assert ok is False
    assert "退款失败" in msg
    # Explicit rejection is FAILED, not a fresh PENDING proposal.
    redis.cas_pending_status.assert_any_await("act_fail", 4, 5, ttl_seconds=service.RESULT_TTL,
                                            fields={"resultMessage": "退款失败：订单状态不允许"})
    redis.delete_pending_action.assert_not_awaited()


@pytest.mark.asyncio
async def test_confirm_system_error_keeps_unknown(service):
    pending = {"token": "act_sys", "userId": "u1", "actionType": "REFUND", "status": 0}

    async def executor(_):
        raise RuntimeError("java 500")

    redis = _redis_stub()
    with patch.object(service, "load_owned", AsyncMock(return_value=pending)):
        with patch("app.services.pending_action_service.redis_service", redis):
            action_type, ok, msg = await service.confirm("u1", "act_sys", executor)

    assert ok is False
    assert "核实" in msg
    redis.cas_pending_status.assert_any_await("act_sys", 4, 6, ttl_seconds=service.RESULT_TTL,
                                            fields={"resultMessage": "正在核实结果，请查询进度；请勿重复申请"})
    redis.delete_pending_action.assert_not_awaited()


@pytest.mark.asyncio
async def test_confirm_cas_rejected(service):
    pending = {"token": "act_cas", "userId": "u1", "actionType": "REFUND", "status": 0}

    async def executor(_):
        raise AssertionError("不应执行 executor")

    redis = _redis_stub(success_cas=False)  # 并发其他请求已落定
    with patch.object(service, "load_owned", AsyncMock(return_value=pending)):
        with patch("app.services.pending_action_service.redis_service", redis):
            result = await service.confirm("u1", "act_cas", executor)
            assert result.status == service.STATUS_EXECUTING
            assert result.success is False


@pytest.mark.asyncio
async def test_confirm_idempotent_on_processed(service):
    pending = {"token": "act_done", "userId": "u1", "status": 1, "resultMessage": "已提交"}

    redis = _redis_stub()
    with patch.object(service, "load_owned", AsyncMock(return_value=pending)):
        with patch("app.services.pending_action_service.redis_service", redis):
            executor = AsyncMock()
            result = await service.confirm("u1", "act_done", executor)
            assert result.success is True
            assert result.message == "已提交"
            executor.assert_not_awaited()
            redis.try_mark_pending_executing.assert_not_awaited()
