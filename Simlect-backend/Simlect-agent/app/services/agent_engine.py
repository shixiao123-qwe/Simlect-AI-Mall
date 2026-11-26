"""
================================================================================
文件：services/agent_engine.py
角色：极薄的「引擎门面」，把编排层与 LangGraph runner 隔开
================================================================================

【这个文件干什么】
agent_service 异步任务只依赖 AgentEngine.assistant_answer，
内部一行转到 graph.runner.run_agent_graph。方便以后换引擎实现而不改编排。

【流程位置】
agent_service._run_agent → agent_engine.assistant_answer → run_agent_graph → nodes...

【如何使用】
业务请用模块单例：await agent_engine.assistant_answer(agent_msg)
不要在 Controller 里直接 import runner（保持分层）。

【关联】agent_service、graph.runner；导读见 docs/智能客服-Java开发者导读.md
================================================================================
"""

import asyncio
from app.services.redis_service import redis_service
from app.graph.runner import run_agent_graph  # LangGraph 图执行入口（节点编排、工具调用等）


class AgentEngine:
    """
    Agent 推理引擎门面类。
    类比 Java 里的 @Service AgentEngine：编排层只依赖本类，不直接依赖 graph 包。
    """

    async def assistant_answer(self, agent_msg: dict) -> None:
        """
        执行一轮完整的 Agent 对话推理。

        参数 agent_msg 需至少包含：
        - userId：用户 ID
        - messageId：本条消息 ID（用于流式推送、落库）
        - userMessage：用户输入正文（可能含商品卡片 JSON）

        无返回值；结果通过 stream_service 推送，并通过 message_service 落库。
        """

        user_id=agent_msg["userId"]
        message_id=int(agent_msg["messageId"])
        client=redis_service.client
        # Reject concurrent turns instead of letting whole-session snapshots overwrite each other.
        lock=client.lock(f"agent:session:turn-lock:{user_id}",timeout=180,blocking=False)
        if not await lock.acquire(blocking=False):
            raise ValueError("当前会话正在处理，请等待本轮完成后再发送")
        try:
            key=f"agent:session:last-message:{user_id}"
            previous=await client.get(key)
            if previous is not None and message_id<int(previous):
                raise ValueError("旧会话请求已失效，请刷新后重试")
            await client.set(key,str(message_id),ex=7*24*3600)
            async with asyncio.timeout(150):
                await run_agent_graph(agent_msg)
        finally:
            # Redis lock release verifies owner; never DEL another worker's lease.
            await lock.release()


agent_engine = AgentEngine()  # 模块级单例，供 agent_service 等 import
