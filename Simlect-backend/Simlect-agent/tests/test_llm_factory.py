from app.config.settings import Settings
from app.services.llm_factory import _resolve_memory_llm_config, create_chat_llm, create_memory_llm
from app.utils.llm_content import llm_text

def test_memory_llm_falls_back_to_chat_config(monkeypatch):
    s = Settings(
        llm_api_key="chat-key",
        llm_base_url="https://chat.example",
        llm_model="chat-model",
        llm_timeout=60,
    )
    api_key, base_url, model, timeout = _resolve_memory_llm_config(s)
    assert api_key == "chat-key"
    assert base_url == "https://chat.example"
    assert model == "chat-model"
    assert timeout == 60

def test_memory_llm_uses_dedicated_config(monkeypatch):
    s = Settings(
        llm_api_key="chat-key",
        llm_base_url="https://chat.example",
        llm_model="chat-model",
        memory_llm_api_key="mem-key",
        memory_llm_base_url="https://mem.example",
        memory_llm_model="mem-model",
        memory_llm_timeout=90,
    )
    api_key, base_url, model, timeout = _resolve_memory_llm_config(s)
    assert api_key == "mem-key"
    assert base_url == "https://mem.example"
    assert model == "mem-model"
    assert timeout == 90

def test_chat_and_memory_llm_streaming_flag(monkeypatch):
    monkeypatch.setattr('app.services.llm_factory.get_settings', lambda: Settings(llm_api_key='unit-test-key'))
    chat = create_chat_llm()
    memory = create_memory_llm()
    assert chat.streaming is True
    assert memory.streaming is False

def test_default_chat_model_is_deepseek_flash():
    # 默认值应与 DeepSeek 现行模型名一致（DeepSeek-V4.1-Flash 的 API 模型 ID 为 deepseek-flash）
    assert Settings.model_fields["llm_model"].default == "deepseek-flash"
    assert Settings.model_fields["llm_fallback_model"].default == "deepseek-flash"


def test_responses_text_and_tool_roundtrip(monkeypatch):
    import json
    import httpx
    from langchain_core.messages import HumanMessage, ToolMessage
    from langchain_openai import ChatOpenAI
    from app.services import llm_factory

    settings = Settings(llm_api_key='unit-test-key', llm_base_url='https://unit.test/v1',
                        llm_model='gpt-6-astra', llm_use_responses_api=True)
    monkeypatch.setattr(llm_factory, 'get_settings', lambda: settings)
    requests = []

    def handle(request):
        assert request.url.path == '/v1/responses'
        payload = json.loads(request.content)
        requests.append(payload)
        assert payload['store'] is False
        if len(requests) == 1:
            output = [{'type': 'function_call', 'id': 'fc_test', 'call_id': 'call_test',
                       'name': 'lookup', 'arguments': '{"item":"demo"}', 'status': 'completed'}]
        else:
            assert any(item.get('type') == 'function_call_output' and item['call_id'] == 'call_test'
                       for item in payload['input'])
            output = [{'type': 'message', 'id': 'msg_test', 'role': 'assistant', 'status': 'completed',
                       'content': [{'type': 'output_text', 'text': 'Found demo', 'annotations': []}]}]
        return httpx.Response(200, json={'id': 'resp_test', 'object': 'response', 'created_at': 1,
                                       'model': 'gpt-6-astra', 'status': 'completed', 'output': output,
                                       'error': None, 'incomplete_details': None})

    with httpx.Client(transport=httpx.MockTransport(handle)) as client:
        monkeypatch.setattr(llm_factory, 'ChatOpenAI', lambda **kwargs: ChatOpenAI(http_client=client, **kwargs))
        llm = create_memory_llm().bind_tools([{'type': 'function', 'function': {
            'name': 'lookup', 'description': 'Look up a demo item',
            'parameters': {'type': 'object', 'properties': {'item': {'type': 'string'}}, 'required': ['item']}}}])
        user = HumanMessage(content='Find demo')
        tool_call = llm.invoke([user])
        assert tool_call.tool_calls[0]['name'] == 'lookup'
        assert tool_call.tool_calls[0]['args'] == {'item': 'demo'}
        result = llm.invoke([user, tool_call, ToolMessage(content='demo exists', tool_call_id='call_test')])
        assert llm_text(result.content) == 'Found demo'


def test_memory_can_override_responses_mode(monkeypatch):
    from app.services import llm_factory
    settings = Settings(llm_api_key='unit-test-key', llm_use_responses_api=True,
                        memory_llm_use_responses_api=False)
    monkeypatch.setattr(llm_factory, 'get_settings', lambda: settings)
    assert create_chat_llm().use_responses_api is True
    assert create_memory_llm().use_responses_api is False


def test_responses_content_preserves_json_without_exposing_reasoning():
    import json
    assert llm_text('legacy plain text') == 'legacy plain text'
    content = [{'type': 'reasoning', 'text': 'not visible'},
               {'type': 'text', 'text': '{"facts":'},
               {'type': 'output_text', 'text': '["demo"]}'}]
    assert json.loads(llm_text(content)) == {'facts': ['demo']}
    assert llm_text(None) == ''
