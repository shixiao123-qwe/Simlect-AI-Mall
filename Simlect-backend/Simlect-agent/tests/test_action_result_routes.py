"""ASGI routing/identity checks without starting application lifespan or external services."""
from unittest.mock import AsyncMock, patch
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.api.deps import require_login, TokenUserInfo
from app.api.routes import agent
from app.services.pending_action_service import ActionResult

@pytest.mark.parametrize("path",["getActionResult","confirmAction"])
def test_action_endpoints_require_login(path):
    response=TestClient(app).post("/api/agent/"+path,data={"actionToken":"act_one"})
    assert response.status_code==200
    assert response.json()["code"]==901 and response.json()["status"]=="error"

def test_recovery_route_passes_session_identity_and_is_read_only():
    app.dependency_overrides[require_login]=lambda:TokenUserInfo(user_id="session-user",token="session-token")
    try:
        with patch.object(agent.pending_action_service,"confirm",new_callable=AsyncMock) as confirm, \
             patch.object(agent.action_execute_service,"execute",new_callable=AsyncMock) as execute, \
             patch.object(agent.action_execute_service,"recover",new_callable=AsyncMock) as recover:
            confirm.return_value=ActionResult("AFTER_SALE_REQUEST",False,"正在核实",6,True)
            response=TestClient(app).post("/api/agent/getActionResult",data={"actionToken":"act_one","userId":"forged"})
            assert response.json()["data"]["actionStatus"]=="UNKNOWN"
            assert response.json()["data"]["retryAllowed"] is True
            assert confirm.await_args.args==("session-user","act_one",None)
            assert confirm.await_args.kwargs["recover_only"] is True
            execute.assert_not_awaited()
    finally:
        app.dependency_overrides.pop(require_login,None)
