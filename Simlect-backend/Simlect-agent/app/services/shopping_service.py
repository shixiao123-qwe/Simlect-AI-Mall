"""Strict recommendation path: recall IDs, then Java validates every returned SKU."""
import asyncio
import json
from decimal import Decimal
from dataclasses import dataclass
import httpx
from pydantic import ValidationError
from app.models.shopping import ShoppingRequirement, canonical, attribute_name
from app.services.java_internal_client import (JavaInternalClient, normalize_keys,
    JavaInternalBusinessError, JavaInternalResponseError)
from app.services.tool_invoke_result import ToolInvokeResult
from app.rag.retriever import rag_retriever, recall_diagnostics
from app.rag.rrf import rrf_merge
from app.utils.biz_payload import build_product_payload
from app.config.settings import get_settings

def price_text(value):
    return f"{Decimal(str(value)):.2f}"

def attribute_text(row):
    attrs=row.get('attributes') or {}
    return '；'.join(f'{key}：{value}' for key,value in sorted(attrs.items())) or '暂无数据'

def preference_matches(row, preferences):
    attrs=row.get('attributes') or {}
    return [p for p in preferences if any(p in str(k)+str(v) and not any(
        marker in str(v) for marker in ('不支持','不具备','并非','暂无','未提供','不是','不适合','不含','无'+p,'非'+p,'不'+p)) for k,v in attrs.items())]

def comparison_conclusion(rows, preferences):
    if not rows:
        return '暂时没有可比较的在售商品。'
    if '游戏' in preferences:
        supported=[]
        for row in rows:
            values='；'.join(str(v) for v in (row.get('attributes') or {}).values())
            if '低延迟' in values and not any(w in values for w in ('不支持','非低延迟','无低延迟')):
                supported.append(row)
        if supported:
            names='、'.join(r['product_name'] for r in supported)
            return f'如果主要用于游戏，可优先考虑{names}：已录入参数标注“低延迟”。具体延迟数值和实测表现暂无数据，不能据此保证游戏体验。'
        conclusion='现有参数不足以判断哪款更适合游戏：这些商品没有可核实的低延迟参数。可以继续筛选明确标注低延迟的商品。'
    else:
        scored=[(len(preference_matches(r,preferences)),r) for r in rows]
        best=max(score for score,_ in scored)
        if best:
            names='、'.join(r['product_name'] for score,r in scored if score==best)
            return f'按已录入的偏好参数，可优先考虑{names}；未提供的性能不作判断。'
        conclusion='现有参数没有足够依据判断哪款性能更好。'
    if len(rows)>1:
        cheapest=min(rows,key=lambda r:Decimal(str(r['price'])))
        highest=max(Decimal(str(r['price'])) for r in rows)
        saving=highest-Decimal(str(cheapest['price']))
        if saving>0:
            conclusion+=f" 若更看重价格，{cheapest['product_name']}为{price_text(cheapest['price'])}元，比所选最高价商品便宜{price_text(saving)}元。"
    return conclusion

RECALL_LIMIT = 50
RECALL_TIMEOUT = 7
RECOMMEND_TIMEOUT = 25

def soft_evidence(row, requirement):
    matches = preference_matches(row, requirement.preferences)
    attrs = row.get("attributes") or {}
    brands = [v for k,v in attrs.items() if attribute_name(k)=="品牌"]
    for brand in requirement.preferred_brands:
        if any(canonical(v)==canonical(brand) for v in brands):
            matches.append("品牌："+brand)
    if requirement.scenario and preference_matches(row,[requirement.scenario]):
        matches.append("场景："+requirement.scenario)
    return list(dict.fromkeys(matches))

def rank_rows(rows, requirement, recall_ids=None):
    positions = {pid:index for index,pid in enumerate(recall_ids or [])}
    ranked = sorted(rows, key=lambda row:(-len(soft_evidence(row,requirement)),
        positions.get(row["product_id"],len(positions)), Decimal(str(row["price"])),
        -int(row["stock"]), str(row["product_id"]), str(row["sku_hash"])))
    selected, seen = [], set()
    for row in ranked:
        if row["product_id"] in seen: continue
        seen.add(row["product_id"])
        row=dict(row)
        matched=soft_evidence(row,requirement)
        row["reason"]="已录入参数匹配："+"、".join(matched) if matched else "通过Java价格、属性及库存校验；软偏好暂无可核实证据"
        selected.append(row)
    return selected[:8]

def comparison_record(row, requirement):
    matched = soft_evidence(row,requirement)
    missing = [p for p in requirement.preferences if p not in preference_matches(row,[p])]
    if requirement.preferred_brands and not any(s.startswith("品牌：") for s in matched): missing.append("品牌偏好")
    if requirement.scenario and "场景："+requirement.scenario not in matched: missing.append("适用场景")
    scenarios=[str(value) for key,value in (row.get("attributes") or {}).items()
               if key in ("场景","适用场景","用途") and str(value) not in ("未知","暂无数据","")]
    return {"productName":row["product_name"], "sku":row["sku_hash"], "price":price_text(row["price"]),
        "stock":row["stock"], "configuration":row.get("attributes") or {},
        "requirementMatch":{"hardConstraints":"Java核验通过", "softEvidence":matched},
        "advantages":matched or ["当前SKU有可售库存且通过硬约束"],
        "limitations":["未提供可核实信息："+"、".join(missing)] if missing else ["未录入的性能和不足未知"],
        "scenario":"；".join(scenarios) if scenarios else "未知"}

def presentation_cards(cards, rows, requirement, mode, selected_count=None):
    values=json.loads(cards)
    found={row["product_id"]:row for row in rows}
    for card in values:
        row=found[card["productId"]]
        card["verified"]={"skuHash":row["sku_hash"], "price":price_text(row["price"]), "stock":row["stock"],
            "attributes":{str(k):str(v) for k,v in list((row.get("attributes") or {}).items())[:12]},
            "reason":row.get("reason") or "Java核验通过"}
        if mode=="compare": card["comparison"]=comparison_record(row,requirement)
    if values:
        labels=[]
        if requirement.category:labels.append(requirement.category)
        if requirement.min_price is not None:labels.append("最低 ¥"+price_text(requirement.min_price))
        if requirement.max_price is not None:labels.append("预算 ≤ ¥"+price_text(requirement.max_price))
        labels += [a.name+("不等于" if a.operator=="ne" else "：")+a.value for a in requirement.hard_attributes]
        labels += ["偏好："+p for p in requirement.preferences+requirement.preferred_brands]
        labels += ["排除品牌："+b for b in requirement.excluded_brands]
        values[0]["shopping"]={"mode":mode,"conditions":labels[:20],
            "conclusion":comparison_conclusion(rows,requirement.preferences) if mode=="compare" else f"找到{len(values)}件已核验商品。",
            "unavailableCount":max(0,(selected_count or len(values))-len(values))}
    return json.dumps(values,ensure_ascii=False)

async def verified_rows(ids, requirement):
    ids=list(dict.fromkeys(ids))[:2*RECALL_LIMIT]
    attrs=[a.model_dump() for a in requirement.hard_attributes]
    attrs += [{"name":"品牌","value":brand,"operator":"ne"} for brand in requirement.excluded_brands]
    # Java's existing request cap is preserved. Bound negative brand constraints too.
    if len(attrs)>10: raise ShoppingConstraintsError("Too many hard constraints; simplify requirements")
    async def batch(part):
        data=await JavaInternalClient(timeout=10).post_json('/internal/product/ai/filter',{
            "productIds":part,"minPrice":str(requirement.min_price) if requirement.min_price is not None else None,
            "maxPrice":str(requirement.max_price) if requirement.max_price is not None else None,
            "category":requirement.category,"hardAttributes":attrs})
        valid=[]
        if not isinstance(data,list): raise JavaInternalResponseError("Invalid Java product result")
        for raw in data:
            if not isinstance(raw,dict): raise JavaInternalResponseError("Invalid Java product row")
            row=normalize_keys(raw)
            # Preserve business attribute labels; normalize transport field names only.
            row["attributes"]=raw.get("attributes") or {}
            try:
                price=Decimal(str(row["price"]));stock=row["stock"]
                if row["product_id"] not in part or not row.get("sku_hash") or not row.get("product_name"):continue
                if not price.is_finite() or price<=0 or price.as_tuple().exponent < -2:continue
                if isinstance(stock,bool) or not isinstance(stock,int) or stock<=0:continue
                if requirement.min_price is not None and price<requirement.min_price:continue
                if requirement.max_price is not None and price>requirement.max_price:continue
                if not isinstance(row["attributes"],dict):continue
            except (KeyError,ValueError,TypeError,ArithmeticError):continue
            valid.append(row)
        if data and not valid:
            raise JavaInternalResponseError("Java product rows violated verification contract")
        return valid
    groups=await asyncio.gather(*(batch(ids[start:start+50]) for start in range(0,len(ids),50)))
    return [row for group in groups for row in group]

@dataclass
class RecallOutcome:
    ids: list[str]
    available: bool = True
    degraded: bool = False
    error_code: str | None = None


class ShoppingConstraintsError(ValueError):
    pass


async def _recall(method, query):
    with recall_diagnostics() as diagnostic:
        try:
            async with asyncio.timeout(RECALL_TIMEOUT):
                ids=await method(query,RECALL_LIMIT)
                if not isinstance(ids,list) or not all(isinstance(pid,str) for pid in ids):
                    return RecallOutcome([],False,True,'INVALID_TOOL_RESPONSE')
                return RecallOutcome(ids,not diagnostic.get('failed',False),
                                     diagnostic.get('degraded',False),diagnostic.get('error_code'))
        except TimeoutError:
            return RecallOutcome([],False,True,'RECALL_TIMEOUT')
        except Exception:
            return RecallOutcome([],False,True,'ES_UNAVAILABLE')


def _java_failure(exc):
    if isinstance(exc,ShoppingConstraintsError):
        return ToolInvokeResult.failure('硬约束过多，请简化条件后重试。','INVALID_ARGUMENTS',status='invalid_args')
    if isinstance(exc,(TimeoutError,httpx.TimeoutException)):
        return ToolInvokeResult.failure('商品核验超时，请稍后重试；未返回未经Java核验的商品。','JAVA_TIMEOUT',retryable=True)
    if isinstance(exc,JavaInternalResponseError):
        return ToolInvokeResult.failure('商品核验返回格式异常，请稍后重试。','JAVA_INVALID_RESPONSE')
    if isinstance(exc,JavaInternalBusinessError):
        return ToolInvokeResult.failure('商品核验未通过，请调整条件或稍后重试。','JAVA_REJECTED')
    return ToolInvokeResult.failure('商品核验服务暂不可用，请稍后重试。','JAVA_UNAVAILABLE',retryable=True)

async def recommend(requirements: dict):
    try:
        req=ShoppingRequirement.model_validate(requirements)
        if not req.category:return ToolInvokeResult.failure("请告诉我想购买的商品品类，例如蓝牙耳机。",'INVALID_ARGUMENTS',status='invalid_args')
        if get_settings().internal_read_backend!='http':return ToolInvokeResult.failure("智能导购仅支持Java HTTP内部核验接口。",'READ_BACKEND_UNSUPPORTED')
        async with asyncio.timeout(RECOMMEND_TIMEOUT):
            methods=[rag_retriever.search_product_keyword_ids]
            if get_settings().vector_search_enabled:
                methods.append(rag_retriever.search_product_vector_ids)
            outcomes=await asyncio.gather(*(_recall(method,req.category) for method in methods))
            keyword=outcomes[0].ids
            vector=outcomes[1].ids if len(outcomes)>1 else []
            codes=list(dict.fromkeys(o.error_code for o in outcomes if o.error_code))
            is_degraded=any(o.degraded for o in outcomes)
            # No top-40 truncation before hard filtering: every distinct bounded candidate is checked.
            ids=rrf_merge(keyword,vector,2*RECALL_LIMIT)
            if not ids:
                if all(not o.available for o in outcomes):
                    return ToolInvokeResult(content='商品检索暂不可用，请稍后重试。',status='error',
                        error_code=codes[0] if codes else 'ES_UNAVAILABLE',retryable=True,
                        degraded=True,degradation_codes=codes)
                return ToolInvokeResult(content="没有找到候选商品；请调整品类后重试。",status='empty',
                    error_code='NO_CANDIDATES',degraded=is_degraded,degradation_codes=codes)
            try:
                checked=await verified_rows(ids,req)
            except Exception as exc:
                return _java_failure(exc)
            rows=rank_rows(checked,req,ids)
            if not rows:return ToolInvokeResult(content="没有符合全部硬约束且有库存的商品；缺失属性不会被视为满足条件。",
                status='empty',error_code='NO_MATCHES',degraded=is_degraded,degradation_codes=codes)
            cards,biz=build_product_payload([{**r,"min_price":r["price"]} for r in rows])
            cards=presentation_cards(cards,rows,req,"recommend")
            lines=[f"{index}. **{r['product_name']}**；¥{price_text(r['price'])}；库存{r['stock']}件。\n   {attribute_text(r)}。{r['reason']}。" for index,r in enumerate(rows,1)]
            degraded="部分召回分支降级，以下商品仍已由Java核验。\n" if is_degraded else ""
            return ToolInvokeResult(content=degraded+"找到以下符合条件且有货的商品：\n\n"+'\n\n'.join(lines)+"\n\n价格和库存以下单时再次核验为准。",
                assistant_cards=cards,biz_data=biz,biz_type="product_search",
                product_ids=[r["product_id"] for r in rows],product_names=[r["product_name"] for r in rows],
                degraded=is_degraded,degradation_codes=codes)
    except ValidationError:
        return ToolInvokeResult.failure('商品筛选条件格式不正确，请调整后重试。','INVALID_ARGUMENTS',status='invalid_args')
    except TimeoutError:
        return ToolInvokeResult.failure('推荐查询超时，请稍后重试；未返回未经Java核验的商品。','RECOMMEND_TIMEOUT',retryable=True)
    except Exception:
        return ToolInvokeResult.failure('推荐查询暂不可用，未返回未经Java核验的商品。','INTERNAL_ERROR')

async def compare(ids: list[str], preferences: list[str], requirements=None, sku_hashes=None):
    if not isinstance(ids,list) or not all(isinstance(pid,str) for pid in ids) or len(ids) not in (2,3) or len(set(ids))!=len(ids):return ToolInvokeResult.failure("请选择2至3款不同商品进行对比。",'INVALID_ARGUMENTS',status='invalid_args')
    if not isinstance(preferences,list) or not all(isinstance(p,str) for p in preferences):
        return ToolInvokeResult.failure('商品对比偏好格式不正确。','INVALID_ARGUMENTS',status='invalid_args')
    if not isinstance(sku_hashes,dict) or not sku_hashes or any(not sku_hashes.get(pid) for pid in ids):
        return ToolInvokeResult.failure("缺少当前展示SKU，请重新推荐后选择第几款对比。",'INVALID_ARGUMENTS',status='invalid_args')
    try:
        async with asyncio.timeout(15):
            req=ShoppingRequirement.model_validate(requirements or {})
            req=req.model_copy(update={"preferences":list(dict.fromkeys(req.preferences+preferences))})
            try:
                checked=await verified_rows(ids,req)
            except Exception as exc:
                return _java_failure(exc)
            # Recheck current stock and price, but NEVER silently switch to another SKU.
            found={r["product_id"]:r for r in checked if sku_hashes.get(r["product_id"])==r["sku_hash"]}
            ordered=[found[pid] for pid in ids if pid in found]
            lines=["**对比结论**",comparison_conclusion(ordered,req.preferences),"\n**商品详情**"]
            for index,row in enumerate(ordered,1):
                record=comparison_record(row,req)
                lines.append(f"{index}. **{row['product_name']}**\n价格：¥{record['price']}；库存：{row['stock']}。\n核心配置：{attribute_text(row)}。\n需求匹配：价格、属性及库存核验通过；"+"、".join(record["requirementMatch"]["softEvidence"])+"。\n优势："+"；".join(record["advantages"])+"。\n不足："+"；".join(record["limitations"])+"。\n适用场景："+record["scenario"])
            if len(ordered)<len(ids):lines.append("部分原展示SKU已不满足条件、无库存或下架；未切换其他SKU，也未复用旧数据。")
            cards,biz=build_product_payload([{**r,"min_price":r["price"]} for r in ordered])
            return ToolInvokeResult(content="\n".join(lines),assistant_cards=presentation_cards(cards,ordered,req,"compare",len(ids)) if ordered else None,
                biz_data=biz,biz_type="product_search",product_ids=[r["product_id"] for r in ordered],product_names=[r["product_name"] for r in ordered],
                status='success' if ordered else 'empty',error_code=None if ordered else 'NO_COMPARABLE_PRODUCTS',
                degraded=len(ordered)<len(ids),degradation_codes=['SKU_UNAVAILABLE'] if len(ordered)<len(ids) else [])
    except ValidationError:
        return ToolInvokeResult.failure('商品对比条件格式不正确，请调整后重试。','INVALID_ARGUMENTS',status='invalid_args')
    except TimeoutError:
        return ToolInvokeResult.failure('商品对比超时，请稍后重试；未返回旧结果。','RECOMMEND_TIMEOUT',retryable=True)
    except Exception:
        return ToolInvokeResult.failure('商品对比暂不可用，未返回旧结果。','INTERNAL_ERROR')
