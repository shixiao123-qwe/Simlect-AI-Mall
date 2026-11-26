"""User-confirmed actions: stable proposals, durable result cache and read-before-retry recovery."""
import json
import time
import uuid
from dataclasses import dataclass
import structlog
from app.constants import PENDING_ACTION_TTL
from app.services.redis_service import redis_service
from app.utils.biz_payload import ACTION_LABELS

logger = structlog.get_logger()

@dataclass(frozen=True)
class ActionResult:
    action_type: str | None
    success: bool
    message: str
    status: int
    retry_allowed: bool = False

    def __iter__(self):
        # Preserve the existing internal tuple protocol.
        yield self.action_type
        yield self.success
        yield self.message

    def __getitem__(self, index):
        return (self.action_type, self.success, self.message)[index]

class PendingActionService:
    STATUS_PENDING = 0
    STATUS_SUCCEEDED = STATUS_CONFIRMED = 1
    STATUS_CANCELLED = 2
    STATUS_EXPIRED = 3
    STATUS_EXECUTING = 4
    STATUS_FAILED = 5
    STATUS_UNKNOWN = 6
    RESULT_TTL = 7 * 24 * 3600
    STATUS_NAMES = {0: "PENDING", 1: "SUCCEEDED", 2: "CANCELLED", 3: "EXPIRED",
                    4: "EXECUTING", 5: "FAILED", 6: "UNKNOWN"}

    async def create_pending(self, action_type, user_id, params, summary):
        await redis_service.ensure_connected()
        token = f"act_{uuid.uuid4().hex}"
        params = dict(params)
        request_id = params.get("requestId") or uuid.uuid4().hex
        params["requestId"] = request_id
        label, confirm_text, risk_tip = ACTION_LABELS.get(action_type, (action_type, "确认", ""))
        now = int(time.time() * 1000)
        pending = {"token": token, "userId": user_id,
                   "messageId": await redis_service.get_bound_message_id(user_id),
                   "actionType": action_type, "requestId": request_id,
                   "paramsJson": json.dumps(params, ensure_ascii=False), "summary": summary,
                   "confirmText": confirm_text, "riskTip": risk_tip, "status": self.STATUS_PENDING,
                   "createTime": now, "expiresAt": now + PENDING_ACTION_TTL * 1000}
        # Confirmation expires independently of the retained result/recovery record.
        await redis_service.save_pending_action(token, pending, ttl_seconds=self.RESULT_TTL)
        return pending

    async def get_by_token(self, token):
        await redis_service.ensure_connected()
        return await redis_service.get_pending_action(token)

    async def load_owned(self, user_id, token):
        pending = await self.get_by_token(token)
        if not pending:
            raise ValueError("操作已过期或不存在")
        if pending.get("userId") != user_id:
            raise ValueError("无权操作该请求")
        return pending

    def _result(self, pending, status, message=None, retry_allowed=False):
        defaults = {0: "等待用户确认", 1: "申请已提交，请查看售后进度；不代表退款成功",
                    2: "已取消操作", 3: "确认已过期，请重新发起", 4: "正在提交，请稍后核实结果",
                    5: "申请未提交", 6: "正在核实结果，请查询进度；请勿重新生成申请"}
        return ActionResult(pending.get("actionType"), status == self.STATUS_SUCCEEDED,
                            message or pending.get("resultMessage") or defaults[status], status, retry_allowed)

    async def _finish(self, pending, expect, status, message):
        try:
            await redis_service.cas_pending_status(pending["token"], expect, status,
                    ttl_seconds=self.RESULT_TTL, fields={"resultMessage": message})
        except Exception:
            # A failed cache update cannot turn Java's verified success into a business failure.
            logger.warning("pending_result_save_failed", token=pending["token"], status=status)
        return self._result(pending, status, message)

    async def _resolve(self, pending, resolver):
        if resolver is None:
            return {"state": "UNKNOWN"}
        try:
            return await resolver(pending)
        except Exception:
            logger.warning("pending_result_query_failed", token=pending["token"])
            return {"state": "UNKNOWN"}

    async def confirm(self, user_id, token, executor, resolver=None, *, recover_only=False):
        await redis_service.ensure_connected()
        # Ownership is checked even when a different user is currently holding the action lock.
        pending = await self.load_owned(user_id, token)
        cached_state = pending.get("status")
        if cached_state in (self.STATUS_FAILED, self.STATUS_CANCELLED, self.STATUS_EXPIRED) or (
                cached_state == self.STATUS_SUCCEEDED and pending.get("resultMessage")):
            return self._result(pending, cached_state)
        owner = await redis_service.try_lock_pending_action(token, ttl_seconds=120)
        if owner is None:
            return self._result(pending, self.STATUS_EXECUTING)
        try:
            pending = await self.load_owned(user_id, token)
            state = pending.get("status", self.STATUS_PENDING)
            # Legacy CONFIRMED was saved BEFORE execution: it is not proof of success.
            if state == self.STATUS_SUCCEEDED and not pending.get("resultMessage"):
                state = self.STATUS_UNKNOWN
            if state in (self.STATUS_SUCCEEDED, self.STATUS_FAILED, self.STATUS_CANCELLED, self.STATUS_EXPIRED):
                return self._result(pending, state)
            if state == self.STATUS_PENDING:
                expires = pending.get("expiresAt") or pending.get("createTime", int(time.time()*1000)) + PENDING_ACTION_TTL*1000
                if time.time()*1000 >= expires:
                    return await self._finish(pending, self.STATUS_PENDING, self.STATUS_EXPIRED, "确认已过期，请重新发起")
                if recover_only:
                    return self._result(pending, self.STATUS_PENDING)
                if not await redis_service.cas_pending_status(token, self.STATUS_PENDING, self.STATUS_EXECUTING,
                        ttl_seconds=self.RESULT_TTL):
                    return self._result(pending, self.STATUS_EXECUTING)
            else:
                recovery = await self._resolve(pending, resolver)
                if recovery.get("state") == "SUCCEEDED":
                    return await self._finish(pending, pending["status"], self.STATUS_SUCCEEDED, recovery["message"])
                can_retry = pending.get("actionType") == "AFTER_SALE_REQUEST" and recovery.get("state") == "NOT_FOUND" and recovery.get("retryAllowed") is True
                if recover_only or not can_retry:
                    result = await self._finish(pending, pending["status"], self.STATUS_UNKNOWN, "正在核实结果，请查询进度；请勿重新生成申请")
                    return ActionResult(result.action_type, False, result.message, result.status, can_retry)
                # Explicit re-confirmation only; Java's same-ID gate serializes even a delayed original request.
                if not await redis_service.cas_pending_status(token, pending["status"], self.STATUS_EXECUTING,
                        ttl_seconds=self.RESULT_TTL):
                    return self._result(pending, self.STATUS_EXECUTING)
            try:
                message = await executor(pending)
            except ValueError as error:
                return await self._finish(pending, self.STATUS_EXECUTING, self.STATUS_FAILED, str(error))
            except Exception:
                await self._finish(pending, self.STATUS_EXECUTING, self.STATUS_UNKNOWN, "正在核实结果，请查询进度；请勿重复申请")
                recovery = await self._resolve(pending, resolver)
                if recovery.get("state") == "SUCCEEDED":
                    return await self._finish(pending, self.STATUS_UNKNOWN, self.STATUS_SUCCEEDED, recovery["message"])
                return self._result(pending, self.STATUS_UNKNOWN, "正在核实结果，请查询进度；请勿重复申请")
            return await self._finish(pending, self.STATUS_EXECUTING, self.STATUS_SUCCEEDED, message)
        finally:
            # Cleanup failure must not mask a verified result; owner-checked lock expires naturally.
            for cleanup in (lambda: redis_service.clear_pending_executing(token),
                            lambda: redis_service.unlock_pending_action(token, owner)):
                try:
                    await cleanup()
                except Exception:
                    logger.warning("pending_cleanup_failed", token=token)

    async def cancel(self, user_id, token):
        await redis_service.ensure_connected()
        await self.load_owned(user_id, token)
        owner = await redis_service.try_lock_pending_action(token, ttl_seconds=30)
        if owner is None:
            raise ValueError("操作处理中，请稍后再试")
        try:
            pending = await self.load_owned(user_id, token)
            if pending.get("status") == self.STATUS_CANCELLED:
                return
            if not await redis_service.cas_pending_status(token, self.STATUS_PENDING, self.STATUS_CANCELLED,
                    ttl_seconds=self.RESULT_TTL, fields={"resultMessage": "已取消操作"}):
                raise ValueError("该操作正在执行或已处理，不能取消")
        finally:
            try:
                await redis_service.unlock_pending_action(token, owner)
            except Exception:
                logger.warning("pending_cancel_unlock_failed", token=token)

pending_action_service = PendingActionService()
