"""Read facts via Java; CREATE tool only creates a user-owned Redis proposal."""
import json
import uuid
from app.services.java_internal_client import JavaInternalClient, normalize_keys
from app.services.tool_invoke_result import ToolInvokeResult
from app.services.pending_action_service import pending_action_service
from app.services.after_sale_policy import query_policy

client=JavaInternalClient(timeout=10)
TYPES={'refund':'退款','return_refund':'退货退款','exchange':'换货'}

async def order_detail(user_id: str, order_id: str) -> ToolInvokeResult:
    # Scoped Java list endpoint, never expose unscoped getOrder response.
    rows=await client.list_orders(user_id,order_id=order_id,limit=1)
    if not rows:
        return ToolInvokeResult(content='订单不存在或无权访问。')
    row=rows[0]
    items=await client.list_order_items(order_id)
    safe={'orderId':row.get('order_id'),'status':row.get('order_status'),
          'items':[{'orderItemId':i.get('order_item_id'),'productName':i.get('product_name'),
                    'status':i.get('order_item_status')} for i in items[:30]]}
    return ToolInvokeResult(content='订单详情（仅本人）：'+json.dumps(safe,ensure_ascii=False),order_ids=[order_id])

async def eligibility(user_id: str, order_item_id: str, request_type: str) -> dict:
    if request_type not in TYPES or not order_item_id or len(order_item_id)>40:
        raise ValueError('请提供有效订单项和售后类型。')
    try:
        data=await client.post_json('/internal/order/afterSale/eligibility',
                {'userId':user_id,'orderItemId':order_item_id,'type':request_type})
    except ValueError as exc:
        # Only explicit, known business denials are user-facing. Transport errors and
        # unrecognized payloads still fail closed via the existing MCP error handler.
        safe_denials={'订单项不存在或无权访问','不支持的售后类型'}
        if str(exc) not in safe_denials:
            raise
        return {'eligible':False,'reason':str(exc)}
    if not isinstance(data,dict) or not isinstance(data.get('eligible'),bool):
        raise ValueError('资格接口返回格式异常，请稍后重试。')
    return data

async def check(user_id: str, order_item_id: str, request_type: str) -> ToolInvokeResult:
    result=await eligibility(user_id,order_item_id,request_type)
    limits = ('\n期限核实：'+str(result['returnWindowReason'])+'。') if result.get('returnWindowReason') else ''
    return ToolInvokeResult(content=f"{TYPES[request_type]}申请资格："+('可提交' if result['eligible'] else '暂不可提交')+
            '。'+str(result.get('reason') or '')+'；依据：本地演示规则 '+str(result.get('policyVersion') or '（版本未返回）')+'。'+limits)

async def propose(user_id: str, order_item_id: str, request_type: str, reason: str) -> ToolInvokeResult:
    if not reason or not reason.strip() or len(reason)>300:
        return ToolInvokeResult(content='请提供1至300字的售后原因；尚未提交申请。')
    data=await eligibility(user_id,order_item_id,request_type)
    if not data['eligible']:
        return ToolInvokeResult(content='暂不能提交申请：'+str(data.get('reason') or '资格未通过'))
    params={'requestId':uuid.uuid4().hex,'orderItemId':order_item_id,'type':request_type,
            'reason':reason.strip(),'orderId':data.get('orderId')}
    summary=f"提交{TYPES[request_type]}申请；订单项 {order_item_id}；原因：{reason.strip()}。仅进入人工审核，不直接退款。"
    if data.get('returnWindowStatus') not in (None, 'NOT_APPLICABLE', 'WITHIN_WINDOW'):
        summary+=' 退货期限尚未核实，需人工审核。'
    pending=await pending_action_service.create_pending('AFTER_SALE_REQUEST',user_id,params,summary)
    return ToolInvokeResult(content=f"{summary} 请核对并点击确认；尚未提交。 【{pending['token']}】")


async def request_progress(user_id: str) -> ToolInvokeResult:
    rows=await client.post_json('/internal/order/afterSale/requests',{'userId':user_id})
    if not isinstance(rows,list):
        raise ValueError('Invalid after-sale progress response')
    if not rows:
        return ToolInvokeResult(content='暂无售后申请。')
    phases={'PENDING_REVIEW':'待人工审核','WAIT_RETURN':'待填写退货物流','WAIT_INSPECTION':'待仓库验货',
            'READY_REFUND':'待管理员执行退款','REFUNDING':'退款处理中','REFUND_FAILED':'退款未完成，待人工核实',
            'REJECTED':'已驳回','COMPLETED':'退款履约完成'}
    lines=['本人的售后申请进度：']
    for row in rows[:10]:
        phase=row.get('phase') or row.get('status')
        lines.append(f"- 申请 {row.get('requestId')}：{phases.get(phase,'状态待核实')}；{row.get('productName') or '订单商品'}")
        if phase=='COMPLETED' and row.get('simulated'):
            lines.append('  本地模拟退款，未发生真实资金退回。')
    lines.append('详情、退货地址及物流填写请打开 [我的售后申请](/after-sale-requests)。')
    return ToolInvokeResult(content='\n'.join(lines))
