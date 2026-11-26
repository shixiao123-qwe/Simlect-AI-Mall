"""Bounded process-lifespan HTTP pool for Java internal calls.

Outside an application lifespan (CLI and isolated asyncio.run calls), clients are
request-scoped and closed immediately. Pools never cross event-loop boundaries.
No cookie or auth state is shared between callers; POST requests are not retried.
"""
import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass
from http.cookiejar import CookieJar, DefaultCookiePolicy

import httpx


class _RejectCookies(DefaultCookiePolicy):
    def set_ok(self, cookie, request):
        return False

    def return_ok(self, cookie, request):
        return False


def _new_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        limits=httpx.Limits(max_connections=12, max_keepalive_connections=6, keepalive_expiry=20),
        cookies=CookieJar(policy=_RejectCookies()),
        follow_redirects=False,
    )


@dataclass
class _Pool:
    client: httpx.AsyncClient
    owners: int = 1


_pools: dict[asyncio.AbstractEventLoop, _Pool] = {}


@asynccontextmanager
async def internal_http_lifespan():
    """Share one client per running loop, close after the outermost owner exits."""
    loop = asyncio.get_running_loop()
    pool = _pools.get(loop)
    if pool is None:
        pool = _Pool(_new_client())
        _pools[loop] = pool
    else:
        pool.owners += 1
    try:
        yield pool.client
    finally:
        pool.owners -= 1
        if pool.owners == 0:
            _pools.pop(loop, None)
            await pool.client.aclose()


@asynccontextmanager
async def internal_http_client():
    pool = _pools.get(asyncio.get_running_loop())
    if pool is not None:
        yield pool.client
    else:
        async with _new_client() as client:
            yield client


class ManagedInternalHttpASGI:
    """Wrap the ASGI *application* lifespan, never individual MCP sessions."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            async with internal_http_lifespan():
                await self.app(scope, receive, send)
        else:
            await self.app(scope, receive, send)
