"""Read-only local acceptance; never creates orders, confirms payment or refunds."""
import json,time,subprocess,urllib.request,urllib.parse,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
results=[]
def req(path,data=None,token=None):
    headers={'X-Requested-With':'XMLHttpRequest','Origin':'http://127.0.0.1:6001'}
    if token: headers['Cookie']='token='+token
    body=urllib.parse.urlencode(data).encode() if data is not None else None
    r=urllib.request.Request(path,data=body,headers=headers)
    try:
        with urllib.request.urlopen(r,timeout=10) as response:
            return response.status,json.loads(response.read())
    except urllib.error.HTTPError as e:
        try: value=json.loads(e.read())
        except Exception: value={}
        return e.code,value

def check(name,fn):
    start=time.monotonic()
    try:
        detail=fn()
        results.append(dict(name=name,passed=True,detail=detail,ms=round((time.monotonic()-start)*1000,2)))
    except Exception as e:
        results.append(dict(name=name,passed=False,error=type(e).__name__,ms=round((time.monotonic()-start)*1000,2)))

def health(port):
    status,r=req(f'http://127.0.0.1:{port}/'+('health' if port in (7050,7060) else 'actuator/health'))
    assert status==200 and r.get('status') in ('UP','ok','healthy'),r.get('status')
    return r.get('status')

def accepted(path,data,token=None):
    status,r=req('http://127.0.0.1:6001/api/'+path,data,token)
    assert status==200 and r.get('code')==200
    return r['data']

def denied(path,data=None):
    status,r=req('http://127.0.0.1:6001/api/'+path,data)
    assert status in (401,403) or r.get('code') in (401,403,901)
    return dict(http=status,code=r.get('code'))

def search():
    data=accepted('search/products',{'keyWords':'蓝牙耳机','priceTo':'300','pageNo':1})
    rows=data['list'];assert rows
    assert all(float(r['minPrice'])<=300 for r in rows)
    return dict(count=len(rows))

def invalid_search():
    status,r=req('http://127.0.0.1:6001/api/search/products',{'keyWords':'耳机','priceFrom':'500','priceTo':'100'})
    assert status<500 and r.get('code') not in (None,200)
    return dict(http=status,code=r.get('code'))

def internal_denied():
    status,r=req('http://127.0.0.1:8080/internal/product/ai/filter',{})
    assert status in (401,403) or r.get('code') in (401,403)
    return dict(http=status,code=r.get('code'))

def progress():
    raw=subprocess.check_output(['docker','exec','simlect-redis','redis-cli','--raw','GET','mall:token:web:userId:9000000001'],text=True,encoding='utf8')
    token=json.loads(raw)['token']
    rows=accepted('order/afterSale/progress',{},token)
    assert all(r['userId']=='9000000001' for r in rows)
    target=[r for r in rows if r['requestId']=='a8ec304a07af4d9c9f55cf9cef0331c5'];assert len(target)==1
    row=target[0];assert row['status']=='COMPLETED' and row['phase']=='COMPLETED' and row['simulated']==1 and float(row['refundAmount'])==399
    return dict(rows=len(rows),status=row['status'],phase=row['phase'],simulated=True,amount=399)

started=time.time()
for sample in range(3):
    for port in (8105,8099,8102,8084,8087,8093,8096,8108,8111,8080,7050,7060):
        check(f'health-{port}-sample-{sample+1}',lambda p=port:health(p))
    if sample<2:time.sleep(20)
check('search-budget-real-es',search)
check('search-invalid-budget-rejected',invalid_search)
check('anonymous-orders-rejected',lambda:denied('order/loadMyOrder',{'pageNo':1}))
check('anonymous-progress-rejected',lambda:denied('order/afterSale/progress',{}))
check('internal-without-token-rejected',internal_denied)
check('demo-completed-refund-readback',progress)
report=dict(mode='read_only_local_acceptance',started_at=started,elapsed_seconds=round(time.time()-started,2),passed=sum(r['passed'] for r in results),total=len(results),results=results,note='Short sequential health sampling is not a soak/load test. No model or financial operation called.')
p=ROOT/'evals/reports/closing-live.json';p.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(report,ensure_ascii=False,indent=2))


raise SystemExit(0 if report['passed']==report['total'] else 1)
