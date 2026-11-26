import asyncio,json,os,sys
from pathlib import Path
from dotenv import dotenv_values
root=Path.cwd();sys.path.insert(0,str(root/'Simlect-backend/Simlect-agent'))
for k,v in dotenv_values(root/'deploy/.env.local').items():
 if v is not None and k.startswith(('SIMLECT_INTERNAL_TOKEN','LLM_','EMBEDDING_','VECTOR_SEARCH_ENABLED')):os.environ[k]=v
os.environ['JAVA_WEB_URL']='http://127.0.0.1:8080';os.environ['MCP_SERVER_URL']='http://127.0.0.1:7060'
from app.services.mcp_streamable_client import mcp_streamable_client
from app.services.tool_invoke_result import parse_tool_wire
async def run():
 raw=await mcp_streamable_client.call_tool('RECOMMEND_PRODUCTS',{'userId':'9000000001','requirements':{'category':'厨具','max_price':'300'}})
 result=parse_tool_wire(raw);cards=json.loads(result.assistant_cards) if result.assistant_cards else []
 assert cards and all(c['productId'].startswith('ABO') and float(c['minPrice'])<=300 for c in cards)
 assert cards[0]['shopping']['mode']=='recommend' and '预算 ≤ ¥300.00' in cards[0]['shopping']['conditions']
 assert all(c['verified']['stock']>0 and float(c['verified']['price'])<=300 for c in cards)
 assert all('集尘袋' not in c['productName'] for c in cards)
 comparison=parse_tool_wire(await mcp_streamable_client.call_tool('COMPARE_PRODUCTS',{'userId':'9000000001','productIds':[c['productId'] for c in cards[:2]],'preferences':[]}))
 compared=json.loads(comparison.assistant_cards)
 assert compared[0]['shopping']['mode']=='compare' and len(compared)==2
 assert [c['productId'] for c in compared]==[c['productId'] for c in cards[:2]]
 assert all('attributes' in c['verified'] for c in compared)
 report={'tool':'RECOMMEND_PRODUCTS','category':'厨具','budget':300,'returned':len(cards),'ids':[c['productId'] for c in cards],'passed':True,'comparison_verified':True,'conditions_verified':True,'classification_verified':True,'mode':'live MCP-Java; no model invoked'}
 (root/'evals/reports/public-catalog-mcp.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(report,ensure_ascii=False))
if __name__=='__main__':asyncio.run(run())

