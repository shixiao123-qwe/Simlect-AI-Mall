"""Versioned MCP tool result contract; text/cards remain backward compatible."""
from __future__ import annotations

import json
from dataclasses import dataclass, field

WIRE_PREFIX = 'SIMLECT_TOOL_RESULT:'
RESULT_STATUSES = frozenset({'success', 'empty', 'error', 'invalid_args', 'denied', 'transport_error', 'legacy'})


@dataclass
class ToolInvokeResult:
    content: str
    biz_type: str | None = None
    biz_data: str | None = None
    assistant_cards: str | None = None
    product_ids: list[str] = field(default_factory=list)
    product_names: list[str] = field(default_factory=list)
    order_ids: list[str] = field(default_factory=list)
    status: str = 'success'
    error_code: str | None = None
    retryable: bool = False
    degraded: bool = False
    legacy: bool = False
    degradation_codes: list[str] = field(default_factory=list)

    @classmethod
    def failure(cls, content: str, error_code: str, *, status: str = 'error', retryable: bool = False):
        # A machine hint is not permission to retry writes. No retry is performed here.
        return cls(content=content, status=status, error_code=error_code, retryable=retryable)

    def to_tool_message(self) -> str:
        return self.content

    def to_biz_dict(self) -> dict | None:
        if not (self.product_ids or self.product_names or self.order_ids):
            return None
        return {'productIds': self.product_ids, 'productNames': self.product_names, 'orderIds': self.order_ids}

    def to_wire(self) -> str:
        # Always preserve status, including errors without cards. New errors never use
        # user-facing text as the machine protocol.
        payload = {
            'wireVersion': 2, 'content': self.content or '',
            'bizType': self.biz_type, 'bizData': self.biz_data, 'assistantCards': self.assistant_cards,
            'productIds': self.product_ids, 'productNames': self.product_names, 'orderIds': self.order_ids,
            'status': self.status, 'errorCode': self.error_code, 'retryable': self.retryable,
            'degraded': self.degraded, 'legacy': self.legacy, 'degradationCodes': self.degradation_codes,
        }
        return WIRE_PREFIX + json.dumps(payload, ensure_ascii=False)


def _invalid_wire() -> ToolInvokeResult:
    # Do not send malformed wire payloads (which may contain internals) to the model.
    return ToolInvokeResult.failure('【工具响应异常】服务返回格式异常，请稍后重试', 'INVALID_TOOL_RESPONSE')


def _legacy_text(raw: str) -> ToolInvokeResult:
    # Compatibility only, not the v2 protocol. Old server errors must not trigger
    # local replay/backfill or be counted as successful remote business operations.
    prefixes = {'【工具执行失败】': 'MCP_PROTOCOL_ERROR', '【工具调用超时】': 'MCP_TIMEOUT',
                '【工具调用失败】': 'MCP_UNAVAILABLE'}
    for prefix, code in prefixes.items():
        if raw.startswith(prefix):
            return ToolInvokeResult(content=raw, status='transport_error', error_code=code, legacy=True)
    return ToolInvokeResult(content=raw, status='legacy', legacy=True)


def parse_tool_wire(text: str | None) -> ToolInvokeResult:
    raw = text or ''
    if not isinstance(raw, str):
        return _invalid_wire()
    if not raw.startswith(WIRE_PREFIX):
        return _legacy_text(raw)
    try:
        obj = json.loads(raw[len(WIRE_PREFIX):])
    except (ValueError, TypeError):
        return _invalid_wire()
    if not isinstance(obj, dict):
        return _invalid_wire()
    version = obj.get('wireVersion')
    if version not in (None, 2):
        return _invalid_wire()
    legacy = version is None
    status = obj.get('status', 'legacy' if legacy else None)
    if not isinstance(status, str) or status not in RESULT_STATUSES:
        return _invalid_wire()
    if not isinstance(obj.get('content', ''), str):
        return _invalid_wire()
    optional = {}
    for camel, snake in [('bizType', 'biz_type'), ('bizData', 'biz_data'), ('assistantCards', 'assistant_cards')]:
        value = obj.get(camel) if camel in obj else obj.get(snake)
        if value is not None and not isinstance(value, str):
            return _invalid_wire()
        optional[snake] = value
    lists = {}
    for camel, snake in [('productIds', 'product_ids'), ('productNames', 'product_names'),
                         ('orderIds', 'order_ids'), ('degradationCodes', 'degradation_codes')]:
        value = obj.get(camel, obj.get(snake, []))
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            return _invalid_wire()
        lists[snake] = value
    for name in ('retryable', 'degraded', 'legacy'):
        if name in obj and not isinstance(obj[name], bool):
            return _invalid_wire()
    code = obj.get('errorCode', obj.get('error_code'))
    if code is not None and (not isinstance(code, str) or not code or len(code) > 64):
        return _invalid_wire()
    if status in {'error', 'invalid_args', 'denied', 'transport_error'} and not code:
        return _invalid_wire()
    if status in {'error', 'invalid_args', 'denied', 'transport_error', 'empty'}:
        # Error/empty status cannot carry stale cards or factual IDs to the UI.
        optional = {'biz_type': None, 'biz_data': None, 'assistant_cards': None}
        lists.update(product_ids=[], product_names=[], order_ids=[])
    return ToolInvokeResult(content=obj.get('content', ''), **optional, **lists, status=status,
                            error_code=code, retryable=obj.get('retryable', False),
                            degraded=obj.get('degraded', False), legacy=legacy or obj.get('legacy', False))
