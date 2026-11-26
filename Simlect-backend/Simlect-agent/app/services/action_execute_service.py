"""
================================================================================
文件：services/action_execute_service.py
角色：用户确认后，真正调 Java 写接口（退款/确认收货/评价…）
================================================================================

【这个文件干什么】
1) JavaBridge：用**用户 token** 表单 POST Gateway /api/order/...
2) ActionExecuteService：按 pending.actionType 分发到 bridge 方法

【流程】
用户点确认 → pending_action_service.confirm(executor=...)
  → ActionExecuteService.execute(pending, token)
  → 解析 paramsJson → 按 actionType 调 JavaBridge 对应方法
  → Java Gateway /api/order/** 执行业务写操作

【为何不用 Internal Token】
写操作必须走用户登录态与 Java 现有鉴权/风控；内部令牌只给 Agent 只读。

【和 java_internal_client 的区别】
| java_internal_client     | 本文件 JavaBridge           |
|--------------------------|----------------------------|
| /internal/** + X-Internal-Token | /api/** + 用户 token  |
| 只读查询                  | 确认后的写操作              |

【关联】
pending_action_service、api/routes/agent.py confirmAction、Java OrderController
================================================================================
"""

import httpx  # import：异步 HTTP 客户端 — 用于 POST 表单到 Java Gateway 用户 API
import structlog  # import：结构化日志 — 便于排查 Java 调用失败

from app.config.settings import get_settings  # import：读取 java_web_url 等配置 — Gateway 地址

logger = structlog.get_logger()  # 获取本模块 logger（当前类内未直接使用，保留供后续扩展）


class JavaBridge:  # Java Web 桥接类 — 封装对用户 /api/** 接口的 HTTP 调用，类比 @FeignClient
    """用用户登录 token 调 Java Gateway /api/** 写接口。"""

    async def _post_form(self, token: str, path: str, data: dict) -> dict:  # 通用表单 POST — 带用户鉴权 token

        settings = get_settings()  # 读取配置 — java_web_url 指向 Gateway
        url = f"{settings.java_web_url.rstrip('/')}/api{path}"  # 拼接用户业务 API 路径

        # async with ~ Java try-with-resources：自动关闭 HTTP 连接
        async with httpx.AsyncClient(timeout=30) as client:  # 30 秒超时 — 写操作可能较慢
            resp = await client.post(
                url,  # 完整 Gateway URL
                data=data,  # application/x-www-form-urlencoded 表单 — 对应 Java @RequestParam
                headers={"token": token, **({"X-Request-Id": str(data["requestId"])} if data.get("requestId") else {})},  # Header 传 token — Java 鉴权过滤器从此读取
                cookies={"token": token},  # Cookie 也传 token — 兼容部分老接口只读 Cookie
            )
            resp.raise_for_status()  # HTTP 非 2xx 抛 httpx.HTTPStatusError
            try:
                result = resp.json()
            except ValueError as error:
                raise RuntimeError("Java response is not valid JSON; outcome unknown") from error
            if not isinstance(result, dict) or result.get("status") not in ("success", "error"):
                raise RuntimeError("Java response is not a recognized result; outcome unknown")
            return result

    async def refund_order(self, token: str, order_item_id: str) -> str:
        raise ValueError("Agent 退款须使用售后申请确认流程")

    async def confirm_order(self, token: str, order_id: str, request_id: str | None = None) -> str:  # 确认收货 — POST /api/order/confirmOrder

        result = await self._post_form(token, "/order/confirmOrder", {"orderId": order_id, "requestId": request_id})  # 表单 POST
        if result.get("status") == "success":
            return f"订单 {order_id} 已确认收货"  # 成功文案
        raise ValueError(result.get("info") or "确认收货失败")  # 失败抛异常

    async def post_comment(
        self,
        token: str,  # 用户登录 token
        order_id: str,  # 订单 ID
        content: str,  # 评价正文
        star: int,  # 星级 1-5
        request_id: str | None = None,
    ) -> str:  # 提交商品评价 — POST /api/order/comment/postComment

        result = await self._post_form(
            token,
            "/order/comment/postComment",  # 评价接口路径
            {"orderId": order_id, "commentContent": content, "star": star, "requestId": request_id},  # 三个表单字段 — camelCase 键名
        )
        if result.get("status") == "success":
            return f"订单 {order_id} 评价成功"
        raise ValueError(result.get("info") or "评价失败")

    async def post_recomment(
        self,
        token: str,  # 用户登录 token
        order_id: str,  # 订单 ID
        content: str,  # 追评正文
        request_id: str | None = None,
    ) -> str:  # 提交追评 — POST /api/order/comment/postReComment

        result = await self._post_form(
            token,
            "/order/comment/postReComment",  # 追评接口路径
            {"orderId": order_id, "reCommentContent": content, "requestId": request_id},  # 追评正文字段名与首次评价不同
        )
        if result.get("status") == "success":
            return f"订单 {order_id} 追评成功"
        raise ValueError(result.get("info") or "追评失败")


java_bridge = JavaBridge()  # 模块级桥接单例 — ActionExecuteService 构造时注入使用


class ActionExecuteService:  # 操作执行服务 — 根据 pending.actionType 分发到 JavaBridge 对应方法

    def __init__(self):
        self._bridge = java_bridge  # 注入 JavaBridge 实例 — 类似 @Autowired private JavaBridge bridge

    async def execute(self, pending: dict, token: str) -> str:  # 执行已确认的 pending — confirm 的 executor 回调入口

        action_type = pending.get("actionType")  # 从 pending Map 取操作类型 — REFUND / CONFIRM_RECEIPT 等
        import json  # 局部 import — 仅在 execute 内使用，避免模块顶部循环依赖
        params = json.loads(pending.get("paramsJson") or "{}")  # JSON 字符串 → dict — 空则解析为 {}

        if action_type == "AFTER_SALE_REQUEST":
            # Client supplies no identity or money. Java derives user from login token.
            payload = self._after_sale_payload(pending)
            result = await self._after_sale_call(token, "create", payload)
            if result.get('status') != 'success':
                if result.get('status') == 'error':
                    raise ValueError(result.get('info') or '售后申请提交失败')
                raise RuntimeError('Unrecognized Java response; outcome unknown')
            row = result.get('data')
            self._validate_application(row, payload)
            return self._submission_message(row)

        if action_type == "REFUND":
            # Historical cards lack a confirmed application type/reason. Never reinterpret
            # an old direct-refund confirmation as consent to a different operation.
            raise ValueError("旧退款确认卡已停用，请重新提供售后类型和原因，生成售后申请确认卡")

        request_id = pending.get("requestId") or params.get("requestId") or pending.get("token")
        if not request_id:
            raise ValueError("缺少原请求标识，请重新发起提案")
        if pending.get("requestId") and params.get("requestId") != pending["requestId"]:
            raise ValueError("请求编号与原确认提案不一致")

        if action_type == "CONFIRM_RECEIPT":  # 确认收货分支
            order_id = params.get("orderId")  # 取订单 ID
            if not order_id:
                raise ValueError("确认收货参数缺失，请重新发起提案")
            return await self._bridge.confirm_order(token, order_id, request_id=request_id)  # 调 Java 确认收货

        if action_type == "PRODUCT_REVIEW":  # 首次评价分支
            order_id = params.get("orderId")  # 订单 ID
            content = params.get("commentContent")  # 评价正文
            star = params.get("star")  # 星级 — 可能是 int 或 str
            if not order_id or not content or star is None:  # star=0 合法但 None 不合法 — 用 is None 判断
                raise ValueError("评价参数缺失，请重新发起提案")
            return await self._bridge.post_comment(token, order_id, content, int(star), request_id=request_id)  # star 强制转 int

        if action_type == "RECOMMENT":  # 追评分支
            order_id = params.get("orderId")
            content = params.get("reCommentContent")  # 追评正文字段 — 与 PRODUCT_REVIEW 的 commentContent 不同
            if not order_id or not content:
                raise ValueError("追评参数缺失，请重新发起提案")
            return await self._bridge.post_recomment(token, order_id, content, request_id=request_id)  # 调 Java 追评接口

        raise ValueError("该操作不支持执行")  # 未知 actionType — 防御性分支，不应走到这里

    def _after_sale_payload(self, pending: dict) -> dict:
        import json
        params = json.loads(pending.get("paramsJson") or "{}")
        required = ("requestId", "orderItemId", "type", "reason")
        if any(not params.get(key) for key in required):
            raise ValueError("售后申请参数缺失，请重新发起")
        if pending.get("requestId", params["requestId"]) != params["requestId"]:
            raise ValueError("请求编号与原确认提案不一致")
        return {key: params[key] for key in required}

    async def _after_sale_call(self, token: str, operation: str, payload: dict) -> dict:
        settings = get_settings()
        async with httpx.AsyncClient(timeout=30, cookies={"token": token}) as client:
            response = await client.post(settings.java_web_url.rstrip('/') + '/api/order/afterSale/' + operation,
                                         json=payload, headers={"token": token})
            response.raise_for_status()
            try:
                result = response.json()
            except ValueError as error:
                raise RuntimeError("Invalid Java response; inspect outcome") from error
            if not isinstance(result, dict):
                raise RuntimeError("Unexpected Java response; inspect outcome")
            return result

    def _validate_application(self, row, payload):
        if not isinstance(row, dict) or any(row.get(key) != payload[key] for key in ("requestId", "orderItemId", "type")) or not row.get("status"):
            raise RuntimeError("After-sale result does not match original request")

    def _submission_message(self, row):
        status = "待人工审核" if row["status"] == "PENDING_REVIEW" else row["status"]
        return f"售后申请 {row['requestId']} 已提交，当前申请状态：{status}。不代表退款或换货已执行，请查看售后进度。"

    async def recover(self, pending: dict, token: str) -> dict:
        if pending.get("actionType") != "AFTER_SALE_REQUEST":
            # Existing comment/receipt APIs do not offer a requestId execution ledger.
            return {"state": "UNKNOWN"}
        payload = self._after_sale_payload(pending)
        result = await self._after_sale_call(token, "result", payload)
        if result.get("status") != "success":
            return {"state": "UNKNOWN"}
        data = result.get("data") or {}
        if data.get("state") == "SUCCEEDED":
            row = data.get("application")
            self._validate_application(row, payload)
            return {"state": "SUCCEEDED", "message": self._submission_message(row)}
        if data.get("state") == "NOT_FOUND" and data.get("requestId") == payload["requestId"]:
            return {"state": "NOT_FOUND", "retryAllowed": data.get("retryAllowed") is True}
        return {"state": "UNKNOWN"}


action_execute_service = ActionExecuteService()  # 模块级单例 — confirmAction API 路由 import 并作为 executor 传入
