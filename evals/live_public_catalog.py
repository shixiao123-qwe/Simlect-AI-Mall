"""Read-only validation of imported ABO rows through storefront and Java stock filter."""
import json,urllib.request,urllib.parse,hashlib,time
from pathlib import Path
from dotenv import dotenv_values
ROOT=Path(__file__).resolve().parents[1];rows=json.loads((ROOT/'data/public-catalog/products.json').read_text(encoding='utf8'));cfg=dotenv_values(ROOT/'deploy/.env.local');checks=[]
def request(path,data=None,internal=False):
 headers={'Origin':'http://127.0.0.1:6001','X-Requested-With':'XMLHttpRequest'}
 if internal:headers['X-Internal-Token']=cfg['SIMLECT_INTERNAL_TOKEN'];headers['Content-Type']='application/json'
 body=json.dumps(data).encode() if internal else (urllib.parse.urlencode(data).encode() if data is not None else None)
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+('8080' if internal else '6001')+path,data=body,headers=headers),timeout=20) as response:return response.read()
def check(name,fn):
 start=time.monotonic()
 try:detail=fn();checks.append(dict(name=name,passed=True,detail=detail,ms=round((time.monotonic()-start)*1000,2)))
 except Exception as e:checks.append(dict(name=name,passed=False,error=type(e).__name__,message=str(e)[:150]))
def detail(row):
 r=json.loads(request('/api/product/getProduct',{'productId':row['productId']}));assert r['code']==200
 assert row['skuHash'] in json.dumps(r['data']);return row['productId']
def image(row):
 data=request('/api/file/getResource?sourceName=public-catalog/'+row['productId']+'.jpg');assert hashlib.sha256(data).hexdigest()==row['imageSha256'];return len(data)
def search():
 r=json.loads(request('/api/search/products',{'keyWords':'公开样本','pageNo':1}));assert r['code']==200 and r['data']['totalCount']==50;return r['data']['totalCount']
def filter_products(budget):
 data={'productIds':[r['productId'] for r in rows],'maxPrice':budget}
 response=json.loads(request('/internal/product/ai/filter',data,True));assert response['code']==200
 got=response['data'];expected={r['productId'] for r in rows if float(r['testPrice'])<=budget and r['testStock']>0}
 assert {r['productId'] for r in got}==expected
 assert all(float(r['price'])<=budget and r['stock']>0 for r in got)
 return dict(budget=budget,returned=len(got))
for row in rows:check('detail-'+row['productId'],lambda r=row:detail(r));check('image-'+row['productId'],lambda r=row:image(r))
check('indexed-fifty',search)
for budget in (100,300,9999):check('java-filter-'+str(budget),lambda b=budget:filter_products(b))
report=dict(passed=sum(r['passed'] for r in checks),total=len(checks),mode='live_read_only_public_catalog',checks=checks)
(ROOT/'evals/reports/public-catalog.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({k:v for k,v in report.items() if k!='checks'}));print(json.dumps([r for r in checks if not r['passed']],ensure_ascii=False))
raise SystemExit(0 if report['passed']==report['total'] else 1)
