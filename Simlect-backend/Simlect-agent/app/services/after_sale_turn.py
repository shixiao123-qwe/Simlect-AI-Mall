"""Bounded after-sale workflow within existing LangGraph, no additional LLM loop."""
import re
from app.services.tool_invoke_result import ToolInvokeResult
from app.utils.order_ids import extract_order_id, extract_order_item_id

def after_sale_intent(text: str) -> str | None:
    if any(k in text for k in ('我的售后','售后进度','退款进度','查看售后申请','查售后')):
        return 'progress'
    if any(k in text for k in ('退款','退货','售后','换货','退钱')):
        if any(k in text for k in ('政策','规则','几天','多久','运费','七天','7天','无理由','到账','怎么','如何','流程')):
            return 'policy'
        if any(k in text for k in ('能不能','能否','是否','可以','资格','咨询')):
            return 'eligibility'
        if any(k in text for k in ('申请','提交','办理','帮我退','我要退')) and not re.search(r'(?:不要|不想|不|别|取消).{0,6}(?:申请|提交|退款|退货|换货)',text):
            return 'apply'
        return 'eligibility'
    if any(k in text for k in ('物流','快递','到哪了','运单','包裹')):
        return 'logistics'
    if any(k in text for k in ('我的订单','查订单','订单详情','查询订单','订单状态')):
        return 'orders'
    return None

async def after_sale_turn(state):
    from app.services.mcp_tool_router import mcp_tool_router
    from app.memory.session_memory_service import session_memory_service
    from app.services.redis_service import redis_service
    text=state.get('user_text') or ''
    intent=after_sale_intent(text)
    reason_match=re.search(r'(?:原因[：:]?|因为)\s*(.+)',text)
    if intent is None and not reason_match:
        return None
    user=state['user_id']
    memory=await session_memory_service.load(user,redis_service.client)
    previous=memory.state.get('afterSaleContext') or {}
    if intent is None:
        if not previous.get('awaitingReason'):
            return None
        intent='apply'
    async def invoke(name,args):
        state['after_sale_tools']=state.get('after_sale_tools',[])+[name]
        return await mcp_tool_router.invoke(name,args,user)
    if intent=='progress':
        return await invoke('QUERY_AFTER_SALE_REQUESTS',{})
    if intent=='policy':
        return await invoke('QUERY_AFTER_SALE_POLICY',{'question':text})
    order_id=extract_order_id(text)
    item_id=extract_order_item_id(text)
    if intent=='orders':
        return await invoke('GET_ORDER_DETAIL',{'orderId':order_id}) if order_id else await invoke('QUERY_ORDERS',{})
    if intent=='logistics':
        if not order_id:
            return ToolInvokeResult(content='请提供要查询物流的订单号。')
        return await invoke('QUERY_LOGISTICS',{'orderId':order_id})
    # Explicit new order without an item must never inherit an old item's target.
    if order_id and not item_id:
        memory.state['afterSaleContext']={}
        await session_memory_service.save(memory,redis_service.client)
        previous={}
    if item_id and item_id != previous.get('orderItemId'):
        previous={}
    if not item_id and not order_id:
        item_id=previous.get('orderItemId')
    kind='return_refund' if '退货' in text else 'exchange' if '换货' in text else 'refund' if any(k in text for k in ('退款','退钱')) else previous.get('type','refund')
    if not item_id:
        if order_id:
            result=await invoke('GET_ORDER_DETAIL',{'orderId':order_id})
            result.content+='\n请明确选择订单项ID；有多个商品时不会自动替您选择。'
            return result
        return ToolInvokeResult(content='请提供本人订单项ID（订单号_序号），以及退款、退货退款或换货类型。可先说“我的订单”。')
    memory.state['afterSaleContext']={'orderItemId':item_id,'type':kind,'awaitingReason':intent=='apply' and not bool(reason_match)}
    await session_memory_service.save(memory,redis_service.client)
    if intent=='apply':
        if not reason_match:
            result=await invoke('CHECK_AFTER_SALE_ELIGIBILITY',{'orderItemId':item_id,'type':kind})
            result.content+='\n如需申请，请补充“原因：具体原因”；之后会生成确认卡，尚未提交。'
            return result
        return await invoke('CREATE_AFTER_SALE_REQUEST',{'orderItemId':item_id,'type':kind,'reason':reason_match.group(1).strip()})
    return await invoke('CHECK_AFTER_SALE_ELIGIBILITY',{'orderItemId':item_id,'type':kind})
