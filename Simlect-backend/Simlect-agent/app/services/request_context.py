"""Request-scoped correlation only; never store identities or authentication here."""
from contextlib import contextmanager
from contextvars import ContextVar
import re
import uuid

TRACE_HEADER = "X-Trace-Id"
_trace_id: ContextVar[str | None] = ContextVar("request_trace_id", default=None)
_HEX = re.compile(r"^[0-9a-fA-F]{32}$")
_UUID = re.compile(r"^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")


def normalize_trace_id(value: str | None) -> str:
    """Accept bounded hexadecimal IDs, replacing malformed/untrusted input."""
    if isinstance(value, str) and (_HEX.fullmatch(value) or _UUID.fullmatch(value)):
        return value.replace("-", "").lower()
    return uuid.uuid4().hex


def get_trace_id() -> str:
    # Outside a scope do not leave sticky state on long-lived worker tasks.
    return _trace_id.get() or uuid.uuid4().hex


@contextmanager
def trace_scope(trace_id: str | None = None):
    resolved = normalize_trace_id(trace_id)
    token = _trace_id.set(resolved)
    try:
        yield resolved
    finally:
        _trace_id.reset(token)


class RequestTraceMiddleware:
    """Pure ASGI middleware: context is inherited by per-request background tasks."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            return await self.app(scope, receive, send)
        values = [value for key, value in scope.get("headers", []) if key.lower() == b"x-trace-id"]
        try:
            incoming = values[0].decode("ascii") if len(values) == 1 else None
        except UnicodeDecodeError:
            incoming = None
        with trace_scope(incoming) as trace_id:
            async def traced_send(message):
                if message["type"] == "http.response.start":
                    message = dict(message)
                    headers = [(k, v) for k, v in message.get("headers", []) if k.lower() != b"x-trace-id"]
                    message["headers"] = headers + [(b"x-trace-id", trace_id.encode("ascii"))]
                await send(message)
            await self.app(scope, receive, traced_send)
