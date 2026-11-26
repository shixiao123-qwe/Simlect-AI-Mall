"""One bounded shopping turn; current visible product/SKU pairs are the comparison boundary."""
import asyncio
import json
import re
from app.services.shopping_parser import parse_shopping
from app.services.tool_invoke_result import ToolInvokeResult

SHOPPING_TURN_TIMEOUT = 45

def comparison_indices(text: str):
    if not any(w in text for w in ("对比","比较","哪款","哪个好")): return []
    digits={"一":1,"二":2,"两":2,"三":3,"四":4,"五":5,"六":6,"七":7,"八":8,"九":9,"十":10}
    matches=re.findall(r"第\s*([一二两三四五六七八九十0-9]+)\s*[款个件]",text)
    return list(dict.fromkeys(int(m) if m.isdigit() else digits.get(m,99) for m in matches))

def visible(memory,result):
    # Only actually rendered cards establish the next turn's reference numbering.
    cards=json.loads(result.assistant_cards) if result.assistant_cards else []
    ids=result.product_ids or []
    pairs={c["productId"]:c.get("verified",{}).get("skuHash") for c in cards
           if c.get("productId") in ids and c.get("verified",{}).get("skuHash")}
    memory.state["shoppingResults"]=[c["productId"] for c in cards if c.get("productId") in pairs]
    memory.state["shoppingShownSkus"]=pairs

async def shopping_turn(state):
    try:
        async with asyncio.timeout(SHOPPING_TURN_TIMEOUT):
            return await _shopping_turn(state)
    except Exception:
        return ToolInvokeResult(content="导购处理暂时超时或不可用，请稍后重试；未返回旧商品或未经核验的商品。")

async def _shopping_turn(state):
    from app.memory.session_memory_service import session_memory_service
    from app.services.redis_service import redis_service
    from app.services.mcp_tool_router import mcp_tool_router
    if state.get("intent") in ("QUERY_ORDER","REFUND","CONFIRM_RECEIPT","CANCEL_ORDER","QUERY_LOGISTICS","PRODUCT_REVIEW","RECOMMENT","QUERY_COUPON","QUERY_COMMENT"):
        return None
    user_id=state["user_id"]
    memory=await session_memory_service.load(user_id,redis_service.client)
    previous=memory.state.get("shoppingRequirement") or {}
    text=state.get("user_text") or ""
    indices=comparison_indices(text)
    comparing=any(word in text for word in ("对比","比较","哪款","哪个好"))
    modifying=any(word in text for word in ("预算","以内","以下","偏好","不要","换成","改成","改为","清除","取消","重置","重新开始","至少","排除","只要","必须"))
    if comparing and not modifying:
        ids=memory.state.get("shoppingResults") or []
        if len(indices) not in (2,3) or min(indices,default=0)<1 or max(indices,default=99)>len(ids):
            return ToolInvokeResult(content="请从当前展示结果中明确选择第几款，最多对比2至3款；不会引用更早一轮商品。")
        state["shopping_tool"]="COMPARE_PRODUCTS"
        result=await mcp_tool_router.invoke("COMPARE_PRODUCTS",{
            "productIds":[ids[i-1] for i in indices],"preferences":["游戏"] if "游戏" in text else [],
            "requirements":previous,"skuHashes":memory.state.get("shoppingShownSkus") or {}},user_id)
        visible(memory,result)
        await session_memory_service.save(memory,redis_service.client)
        return result
    shopping_intent=state.get("intent")=="PRODUCT_SEARCH"
    followup=bool(previous) and (modifying or any(word in text for word in ("续航","延迟","颜色","品牌","场景","内存","存储")))
    if not shopping_intent and not followup:return None
    # Invalidate before parsing or network I/O, including parse errors and clarification turns.
    memory.state["shoppingResults"]=[]
    memory.state["shoppingShownSkus"]={}
    await session_memory_service.save(memory,redis_service.client)
    req,error=await parse_shopping(text,previous)
    if error:
        return ToolInvokeResult(content=error.removeprefix("CLARIFY:") if error.startswith("CLARIFY:") else
            "需求解析暂不可用，请明确品类、预算和必须满足的条件；本次没有降级为未核验的推荐。")
    memory.state["shoppingRequirement"]=req.model_dump(mode="json")
    await session_memory_service.save(memory,redis_service.client)
    state["shopping_requirement"]=req.model_dump(mode="json")
    state["shopping_tool"]="RECOMMEND_PRODUCTS"
    result=await mcp_tool_router.invoke("RECOMMEND_PRODUCTS",{"requirements":req.model_dump(mode="json")},user_id)
    visible(memory,result)
    await session_memory_service.save(memory,redis_service.client)
    return result
