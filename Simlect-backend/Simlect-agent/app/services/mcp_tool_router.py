"""
================================================================================
文件：services/mcp_tool_router.py
角色：工具调用门面（白名单 + 参数校验 + 调 MCP + 本地卡片回填）
================================================================================

【这个文件干什么】
nodes.tools_node 不直接碰 HTTP，统一走 McpToolRouter.invoke：
1) ToolGuardrail：工具是否允许、userId 是否被篡改
2) mcp_streamable_client.call_tool：请求 :7060
3) 对 SEARCH_PRODUCTS / QUERY_ORDERS 可用本地 mcp_tools_service 回填卡片，
   防止 MCP 进程版本旧导致前端卡片丢字段

【流程】
invoke(tool_name, args, user_id)
  → is_allowed 白名单检查
  → 注入 userId + validate_tool_args 参数校验
  → _to_mcp_args 键名规范化（snake → camel）
  → mcp_streamable_client.call_tool 远程调用
  → parse_tool_wire 解析结果
  → _backfill_structured 本地回填卡片（可选）

【如何使用】
await mcp_tool_router.invoke(tool_name, args, user_id) → ToolInvokeResult

【关联】
tool_guard、mcp_streamable_client、mcp_tools_service、nodes.tools_node
【类比】带鉴权的 Feign 网关过滤器 + 防腐层。
================================================================================
"""

from __future__ import annotations  # import：延迟解析类型注解，允许在类定义前引用尚未定义的类型（类似 Java 泛型前向引用）

import structlog  # import：结构化日志库，输出 JSON 风格 key-value 日志，类比 SLF4J + MDC
from dataclasses import replace

from app.harness.guardrails.tool_guard import ToolGuardrail  # import：工具调用护栏 — 白名单校验 + 参数合法性检查
from app.harness.metrics.runtime_sensors import TOOL_CALL_TOTAL  # import：Prometheus Counter 指标 — 按 tool/status 维度计数
from app.services.mcp_streamable_client import mcp_streamable_client  # import：MCP Streamable HTTP 客户端单例，负责调 :7060
from app.services.tool_invoke_result import ToolInvokeResult, parse_tool_wire  # import：工具结果 DTO + Wire 字符串解析器

logger = structlog.get_logger()  # 获取本模块 logger 实例，后续 logger.info/warning/exception 都用它
tool_guard = ToolGuardrail()  # 模块级护栏单例 — 全局共享一份，类似 Java @Component 无状态 Bean


class McpToolRouter:  # MCP 工具路由器 — 门面类，类比 Java @Service，对外只暴露 invoke 入口
    """Dispatches tools via Streamable HTTP MCP server (not in-process)."""
    # ↑ 类 docstring（英文）：通过 HTTP 远程调 MCP 进程，不在 Agent 进程内直接执行业务

    async def invoke(
        self,
        tool_name: str,  # 工具名，如 "SEARCH_PRODUCTS"，须与白名单 / MCP server 注册名一致
        args: dict,  # LLM 传入的参数字典，键可能是 snake_case 或 camelCase
        user_id: str,  # 当前登录用户 ID — 由上层注入，不信任 LLM 传的 userId
    ) -> ToolInvokeResult:  # 返回统一结果 DTO，含 content 文本 + 可选 assistant_cards 卡片

        if not tool_guard.is_allowed(tool_name):  # 白名单检查：工具名不在允许列表则直接拒绝
            TOOL_CALL_TOTAL.labels(tool="unknown", status="denied").inc()
            return ToolInvokeResult.failure("未知工具，请重新说明需求", "TOOL_DENIED", status="denied")

        if args is not None and not isinstance(args, dict):
            TOOL_CALL_TOTAL.labels(tool=tool_name, status="invalid_args").inc()
            return ToolInvokeResult.failure("【操作失败】参数格式不正确", "INVALID_ARGUMENTS", status="invalid_args")

        raw = dict(args or {})  # 浅拷贝参数 Map — dict(args) 新建对象，避免修改调用方原始 dict
        raw["userId"] = user_id  # 强制覆盖 userId — 防止 LLM 伪造他人 ID 做越权操作

        if not tool_guard.validate_tool_args(tool_name, raw, user_id):  # 校验必填字段、类型、用户身份一致性
            TOOL_CALL_TOTAL.labels(tool=tool_name, status="invalid_args").inc()  # 指标：参数校验未通过
            return ToolInvokeResult.failure("【操作失败】用户身份校验未通过", "INVALID_ARGUMENTS", status="invalid_args")

        try:  # try-except 包裹远程调用 — 网络/MCP 异常在此捕获，不向上抛给 LangGraph
            if tool_name == "COMPARE_PRODUCTS":
                from app.memory.session_memory_service import session_memory_service
                from app.services.redis_service import redis_service
                memory = await session_memory_service.load(user_id, redis_service.client)
                selected = raw.get("productIds") or []
                shown = memory.state.get("shoppingResults") or []
                if not selected or any(pid not in shown for pid in selected):
                    TOOL_CALL_TOTAL.labels(tool=tool_name, status="invalid_args").inc()
                    return ToolInvokeResult.failure("只能对比当前展示的商品，请重新选择。", "INVALID_ARGUMENTS", status="invalid_args")
                # Do not trust a model-supplied SKU or old requirements snapshot.
                raw["skuHashes"] = memory.state.get("shoppingShownSkus") or {}
                raw["requirements"] = memory.state.get("shoppingRequirement") or {}
            mcp_args = self._to_mcp_args(tool_name, raw)  # 把 Python 侧 snake_case 键转为 MCP schema 期望的 camelCase
            text = await mcp_streamable_client.call_tool(tool_name, mcp_args)  # 异步 HTTP 调 MCP 进程 :7060，返回 Wire 字符串
            result = parse_tool_wire(text)
            if result.status in {"success", "legacy"}:
                result = await self._backfill_structured(tool_name, mcp_args, result)
            # Transport completion is not business success. Use bounded labels.
            metric_status = "degraded" if result.status == "success" and result.degraded else result.status
            TOOL_CALL_TOTAL.labels(tool=tool_name, status=metric_status).inc()
            return result
        except TypeError as e:  # 参数类型不匹配 — 如 MCP handler 缺必填参数导致 TypeError
            logger.warning("mcp_tool_bad_args", tool=tool_name, error_type=type(e).__name__)
            TOOL_CALL_TOTAL.labels(tool=tool_name, status="bad_args").inc()  # 指标：参数类型/结构错误
            return ToolInvokeResult.failure("【操作失败】参数不完整", "INVALID_ARGUMENTS", status="invalid_args")
        except Exception as e:  # 兜底：网络超时、MCP 500、JSON 解析失败等未预期异常
            logger.warning("mcp_tool_failed", tool=tool_name, error_type=type(e).__name__)
            TOOL_CALL_TOTAL.labels(tool=tool_name, status="error").inc()  # 指标：系统级错误
            return ToolInvokeResult.failure("【操作失败】系统处理异常，请稍后重试", "INTERNAL_ERROR")

    async def _backfill_structured(
        self,
        tool_name: str,  # 当前调用的工具名 — 决定走哪条回填分支
        mcp_args: dict,  # 已规范化为 camelCase 的参数 — 传给本地 mcp_tools_service
        result: ToolInvokeResult,  # MCP 远程返回的解析结果 — 可能被本地结果覆盖
    ) -> ToolInvokeResult:  # 返回最终 ToolInvokeResult — 优先保证前端卡片字段完整
        """Prefer in-process tool logic so MCP process drift cannot serve stale search/order cards."""
        # ↑ 英文 docstring：优先用 Agent 进程内逻辑，防止 MCP 进程版本旧导致搜索/订单卡片缺字段

        if tool_name == "SEARCH_PRODUCTS":  # 商品搜索：始终优先走 Agent 进程内本地实现
            from app.services.mcp_tools_service import tool_search_products  # 延迟 import — 避免模块循环依赖（类似 lazy init）

            local = await tool_search_products(  # 在 Agent 进程内直接调业务层，不经过 MCP HTTP
                str(mcp_args.get("userId") or ""),  # 用户 ID — get 可能返回 None，or "" 兜底空串
                str(mcp_args.get("keyword") or ""),  # 搜索关键词 — 转 str 防止非字符串类型
                mcp_args.get("excludeProductId"),  # 排除的商品 ID — 可为 None，本地函数自行处理
            )
            if local.assistant_cards or local.content:  # 本地结果有卡片或有效文本内容
                if (result.content or "") != (local.content or ""):  # MCP 与本地文案不一致 — 记录差异便于排查
                    logger.info(
                        "search_products_local_override",  # 日志事件名 — 固定字符串便于 ELK 检索
                        mcp_preview=(result.content or "")[:80],  # MCP 返回文本前 80 字符预览
                        local_preview=(local.content or "")[:80],  # 本地文本前 80 字符预览
                    )
                return replace(local, status="legacy", legacy=True) if local.status == "success" else local
            return result  # 本地无有效结果 — 降级保留 MCP 远程返回值

        if result.assistant_cards or result.biz_type:  # MCP 结果已含结构化卡片或 biz_type 标识
            return result  # 无需回填，直接返回 MCP 结果

        if tool_name != "QUERY_ORDERS":  # 除 QUERY_ORDERS 外，其他工具到此直接返回
            return result  # 非订单查询工具 — 不做本地回填

        from app.services.mcp_tools_service import tool_query_orders  # 延迟 import 订单查询本地实现

        local = await tool_query_orders(  # 本地执行订单查询 — 获取完整 assistant_cards
            str(mcp_args.get("userId") or ""),  # 用户 ID
            mcp_args.get("orderId"),  # 订单 ID — None 表示查最近订单列表
        )
        if local.assistant_cards or local.biz_type:  # 本地成功生成卡片或 biz_type
            logger.info("query_orders_cards_backfilled")  # 记录订单卡片回填事件
            return replace(local, status="legacy", legacy=True) if local.status == "success" else local
        return result  # 本地也无卡片 — 保留 MCP 原始结果

    def _to_mcp_args(self, tool_name: str, args: dict) -> dict:  # 私有方法：参数键名 snake_case → camelCase 映射
        """Normalize to camelCase keys expected by MCP tool schemas."""
        # ↑ 英文 docstring：规范化为 MCP 工具 schema 期望的 camelCase 键名

        # lambda 辅助函数 g：按 keys 顺序取 args 中第一个存在且非 None 的值 — 兼容 snake/camel 两种键名
        g = lambda *keys: next((args[k] for k in keys if k in args and args[k] is not None), None)
        uid = g("userId", "user_id")  # 用户 ID — 优先 camelCase，回退 snake_case
        if tool_name == "QUERY_AFTER_SALE_REQUESTS":
            return {"userId":uid}
        if tool_name == "GET_ORDER_DETAIL":
            return {"userId":uid,"orderId":g("orderId","order_id")}
        if tool_name == "QUERY_AFTER_SALE_POLICY":
            return {"userId":uid,"question":g("question") or ""}
        if tool_name in ("CHECK_AFTER_SALE_ELIGIBILITY","CREATE_AFTER_SALE_REQUEST"):
            out={"userId":uid,"orderItemId":g("orderItemId","order_item_id"),"type":g("type")}
            if tool_name == "CREATE_AFTER_SALE_REQUEST":
                out["reason"]=g("reason") or ""
            return out
        if tool_name == "RECOMMEND_PRODUCTS":
            return {"userId":uid,"requirements":g("requirements") or {}}
        if tool_name == "COMPARE_PRODUCTS":
            return {"userId":uid,"productIds":g("productIds") or [],"preferences":g("preferences") or [],
                    "requirements":g("requirements") or {},"skuHashes":g("skuHashes") or {}}

        if tool_name == "SEARCH_PRODUCTS":  # 商品搜索工具参数映射
            out = {"userId": uid, "keyword": g("keyword") or ""}  # 构造 camelCase 请求体 — keyword 默认空串
            ex = g("excludeProductId", "exclude_product_id")  # 排除商品 ID — 兼容两种键名
            if ex is not None:  # 有值才加入 — 避免向 MCP 传 null 导致 schema 校验失败
                out["excludeProductId"] = ex
            return out  # 返回 SEARCH_PRODUCTS 专用参数 Map

        if tool_name == "QUERY_ORDERS":  # 订单查询工具参数映射
            out = {"userId": uid}  # 至少传 userId — 必填
            oid = g("orderId", "order_id")  # 订单 ID — 可选
            if oid is not None:  # 有 orderId 则查单笔，无则查最近列表
                out["orderId"] = oid
            return out

        if tool_name == "GET_PRODUCT_DETAIL":  # 商品详情工具 — 固定 userId + productId 两字段
            return {"userId": uid, "productId": g("productId", "product_id")}

        if tool_name in ("QUERY_LOGISTICS", "QUERY_COMMENT", "PROPOSE_CONFIRM_RECEIPT"):  # 共用 userId+orderId 的工具组
            return {"userId": uid, "orderId": g("orderId", "order_id")}

        if tool_name == "QUERY_USER_COUPONS":  # 优惠券查询 — userId 必填，status 可选筛选
            out = {"userId": uid}
            st = g("status")  # 优惠券状态：0 未使用 / 1 已使用 / 2 已过期
            if st is not None:
                out["status"] = st
            return out

        if tool_name == "PROPOSE_REFUND":  # 退款提案 — 需要 orderItemId（订单项 ID）
            return {"userId": uid, "orderItemId": g("orderItemId", "order_item_id"),
                    "type": g("type"), "reason": g("reason")}

        if tool_name == "PROPOSE_PRODUCT_REVIEW":  # 评价提案 — 四个字段
            return {
                "userId": uid,
                "orderId": g("orderId", "order_id"),  # 订单 ID
                "commentContent": g("commentContent", "comment_content"),  # 评价正文
                "star": g("star"),  # 星级 1-5
            }

        if tool_name == "PROPOSE_RECOMMENT":  # 追评提案 — 三个字段
            return {
                "userId": uid,
                "orderId": g("orderId", "order_id"),  # 订单 ID
                "reCommentContent": g("reCommentContent", "re_comment_content"),  # 追评正文
            }

        return {"userId": uid}  # 未知工具 — 兜底只传 userId，由 MCP 侧决定是否够用


mcp_tool_router = McpToolRouter()  # 模块级单例 — graph/nodes 直接 `from ... import mcp_tool_router` 使用
