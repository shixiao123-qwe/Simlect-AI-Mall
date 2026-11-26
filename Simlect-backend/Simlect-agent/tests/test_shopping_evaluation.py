"""Fixed T09 corpus + service fixtures. This measures local contracts, NOT live model/catalog quality."""
import asyncio
import json
import time
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
import pytest
from app.services import shopping_service as service
from app.services.shopping_parser import parse_shopping
from app.models.shopping import ShoppingRequirement, canonical

CASES=json.loads((Path(__file__).parent/"data/t09_shopping_cases.json").read_text(encoding="utf-8"))
STATS={"mode":"deterministic fixtures; no live LLM/ES/Java", "requirements_total":0,"requirements_passed":0,
       "budget_rows":0,"budget_rows_valid":0,"recommendable_requests":0,"recommendations_succeeded":0,
       "comparison_rows":0,"comparison_rows_complete":0,"comparison_known_fields":0,"recommendation_requests":0,"tool_attempts":0,"tool_successes":0,"latency_ms":[]}
@pytest.fixture(scope="module",autouse=True)
def write_metrics():
    yield
    def rate(n,d):return round(n/d,6) if d else None
    STATS["budget_hard_constraint_satisfaction"]=rate(STATS["budget_rows_valid"],STATS["budget_rows"])
    STATS["recommendation_success_rate"]=rate(STATS["recommendations_succeeded"],STATS["recommendation_requests"])
    STATS["eligible_recommendation_success_rate"]=rate(STATS["recommendations_succeeded"],STATS["recommendable_requests"])
    STATS["comparison_structural_completeness"]=rate(STATS["comparison_rows_complete"],STATS["comparison_rows"])
    STATS["comparison_data_completeness"]=rate(STATS["comparison_known_fields"],STATS["comparison_rows"]*9)
    STATS["tool_call_success_rate"]=rate(STATS["tool_successes"],STATS["tool_attempts"])
    STATS["average_response_ms"]=round(sum(STATS["latency_ms"])/len(STATS["latency_ms"]),3) if STATS["latency_ms"] else None
    output=Path(__file__).resolve().parents[3]/"evals/t09-shopping-metrics.json"
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(STATS,ensure_ascii=False,indent=2),encoding="utf-8")

@pytest.mark.asyncio
@pytest.mark.parametrize("case",CASES,ids=[c["id"]+":"+c["text"] for c in CASES])
async def test_fixed_requirement_corpus(case):
    start=time.perf_counter();STATS["requirements_total"]+=1
    structured=SimpleNamespace(ainvoke=AsyncMock(return_value=case["patch"]))
    llm=Mock();llm.with_structured_output.return_value=structured
    try:
        with patch("app.services.shopping_parser.create_memory_llm",return_value=llm):
            req,error=await parse_shopping(case["text"],case["previous"])
        if case["clarify"]:
            assert req is None and error.startswith("CLARIFY:")
        else:
            assert error is None,error
            actual=req.model_dump(mode="json")
            for key,value in case["expected"].items():assert actual[key]==value,(case["text"],key,actual[key],value)
        llm.with_structured_output.assert_called_once()
        structured.ainvoke.assert_awaited_once()
        STATS["requirements_passed"]+=1
    finally:STATS["latency_ms"].append((time.perf_counter()-start)*1000)

def row(pid="p1",price="299.99",stock=3,brand="索尼",sku=None):
    return {"productId":pid,"productName":"fixture耳机"+pid,"skuHash":sku or "sku-"+pid,"price":price,"stock":stock,
            "attributes":{"品牌":brand,"颜色":"黑色","成色":"全新","场景":"办公"}}

@pytest.fixture
def java(monkeypatch):
    async def post(path,body):
        STATS["tool_attempts"]+=1
        assert path=="/internal/product/ai/filter" and len(body["productIds"])<=50
        STATS["tool_successes"]+=1
        return [row(pid) for pid in body["productIds"]]
    client=SimpleNamespace(post_json=AsyncMock(side_effect=post))
    monkeypatch.setattr(service,"JavaInternalClient",Mock(return_value=client))
    # These scenarios explicitly exercise both recall routes; production's
    # vector-off case is separately covered by test_tool_result_contract.py.
    monkeypatch.setattr(service,"get_settings",lambda:SimpleNamespace(internal_read_backend="http",vector_search_enabled=True))
    monkeypatch.setattr(service.rag_retriever,"search_product_keyword_ids",AsyncMock(return_value=["p1","p2"]))
    monkeypatch.setattr(service.rag_retriever,"search_product_vector_ids",AsyncMock(return_value=[]))
    return client

@pytest.mark.asyncio
@pytest.mark.parametrize("mode",["normal","vector-fails","keyword-fails","vector-timeout","tail-budget","brand-exclusion","hard-attribute"])
async def test_recommendation_scenarios_and_metrics(java,monkeypatch,mode):
    start=time.perf_counter();STATS["recommendable_requests"]+=1;STATS["recommendation_requests"]+=1
    req={"category":"耳机","min_price":"100","max_price":"300"}
    if mode in ("vector-fails","vector-timeout"):
        async def broken(*args):
            if mode=="vector-timeout":await asyncio.sleep(.1)
            raise TimeoutError("vector failed")
        monkeypatch.setattr(service,"RECALL_TIMEOUT",.01)
        service.rag_retriever.search_product_vector_ids.side_effect=broken
    if mode=="keyword-fails":
        service.rag_retriever.search_product_keyword_ids.side_effect=TimeoutError("ES unavailable")
        service.rag_retriever.search_product_vector_ids.return_value=["p1"]
    if mode=="tail-budget":
        service.rag_retriever.search_product_keyword_ids.return_value=[f"p{i}" for i in range(50)]
        service.rag_retriever.search_product_vector_ids.return_value=[f"p{i}" for i in range(50,100)]
        async def tail(path,body):
            STATS["tool_attempts"]+=1;STATS["tool_successes"]+=1
            assert len(body["productIds"])<=50
            # Match the Java filter contract: only budget-compliant rows return.
            # p99 remains in the final batch, guarding against early truncation.
            return [row(pid,"199") for pid in body["productIds"] if pid=="p99"]
        java.post_json.side_effect=tail
    if mode in ("brand-exclusion","hard-attribute"):
        if mode=="brand-exclusion":req["excluded_brands"]=["苹果"]
        else:req["hard_attributes"]=[{"name":"成色","value":"全新","operator":"eq"}]
        original=java.post_json.side_effect
        async def checked(path,body):
            assert any(a["name"]==("品牌" if mode=="brand-exclusion" else "成色") for a in body["hardAttributes"])
            return await original(path,body)
        java.post_json.side_effect=checked
    try:
        result=await service.recommend(req);assert result.product_ids,result.content
        cards=json.loads(result.assistant_cards)
        if mode=="tail-budget":assert result.product_ids==["p99"]
        for card in cards:
            amount=Decimal(card["verified"]["price"]);STATS["budget_rows"]+=1
            valid=Decimal("100")<=amount<=Decimal("300") and card["verified"]["stock"]>0
            STATS["budget_rows_valid"]+=int(valid);assert valid
        STATS["recommendations_succeeded"]+=1
    finally:STATS["latency_ms"].append((time.perf_counter()-start)*1000)

@pytest.mark.asyncio
@pytest.mark.parametrize("mode",["normal","stock-changed","price-changed","unknown-attributes"])
async def test_comparison_scenarios_and_metrics(java,mode):
    start=time.perf_counter()
    async def current(path,body):
        STATS["tool_attempts"]+=1;STATS["tool_successes"]+=1
        rows=[row("p1"),row("p2")]
        if mode=="stock-changed":rows[1]["stock"]=0
        if mode=="price-changed":rows[1]["price"]="500"
        if mode=="unknown-attributes":rows[1]["attributes"]={}
        rows.append(row("p2","100",sku="other-sku"))
        return rows
    java.post_json.side_effect=current
    try:
        result=await service.compare(["p1","p2"],["低延迟"],{"category":"耳机","max_price":"300"},{"p1":"sku-p1","p2":"sku-p2"})
        cards=json.loads(result.assistant_cards);assert cards
        assert result.product_ids==(["p1"] if mode in ("stock-changed","price-changed") else ["p1","p2"])
        for card in cards:
            record=card["comparison"];STATS["comparison_rows"]+=1
            required={"productName","sku","price","stock","configuration","requirementMatch","advantages","limitations","scenario"}
            complete=required<=record.keys() and bool(record["sku"]) and record["stock"]>0
            STATS["comparison_rows_complete"]+=int(complete);assert complete
            STATS["comparison_known_fields"]+=sum((bool(record["productName"]),bool(record["sku"]),bool(record["price"]),
                record["stock"]>0,bool(record["configuration"]),bool(record["requirementMatch"]),bool(record["advantages"]),
                not any("未知" in text or "未提供" in text for text in record["limitations"]),record["scenario"]!="未知"))
            assert record["sku"]=="sku-"+card["productId"]
        if mode=="unknown-attributes":assert cards[1]["comparison"]["scenario"]=="未知"
    finally:STATS["latency_ms"].append((time.perf_counter()-start)*1000)

@pytest.mark.asyncio
@pytest.mark.parametrize("mode",["no-budget-match","no-stock","java-unavailable","both-retrievals-fail"])
async def test_empty_and_failure_cases_are_not_success_metrics(java,mode):
    start=time.perf_counter()
    STATS["recommendation_requests"]+=1
    if mode=="java-unavailable":
        async def outage(*args):
            STATS["tool_attempts"]+=1;raise TimeoutError("Java unavailable")
        java.post_json.side_effect=outage
    elif mode in ("no-budget-match","no-stock"):
        async def empty(path,body):
            STATS["tool_attempts"]+=1;STATS["tool_successes"]+=1
            # Java excludes the over-budget / zero-stock row before returning.
            return []
        java.post_json.side_effect=empty
    else:
        service.rag_retriever.search_product_keyword_ids.side_effect=TimeoutError()
        service.rag_retriever.search_product_vector_ids.side_effect=TimeoutError()
    result=await service.recommend({"category":"耳机","max_price":"300"})
    assert not result.product_ids and not result.assistant_cards
    if mode in ("no-budget-match","no-stock"):
        assert result.status=='empty' and result.error_code=='NO_MATCHES'
    STATS["latency_ms"].append((time.perf_counter()-start)*1000)
