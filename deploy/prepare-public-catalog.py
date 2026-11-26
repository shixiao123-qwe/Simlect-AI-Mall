"""Bounded ABO CC-BY-4.0 sample adapter. No database access or paid model calls."""
import argparse,csv,gzip,hashlib,html,io,json,re,shutil,urllib.request,time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'data/public-catalog';CACHE=OUT/'cache';BASE='https://amazon-berkeley-objects.s3.amazonaws.com/'
SOURCE='https://amazon-berkeley-objects.s3.amazonaws.com/index.html'
LICENSE='https://creativecommons.org/licenses/by/4.0/'
GROUPS={'CELLULAR_PHONE_CASE':('A9101','手机壳',69),'OFFICE_PRODUCTS':('A9102','办公用品',129),'KITCHEN':('A9103','厨具',169),'HOME':('A9104','家居用品',199),'SPORTING_GOODS':('A9105','运动用品',159),'LAMP':('A9106','灯具',189),'LIGHT_BULB':('A9106','灯具',189)}
FIELDS=[('brand','品牌'),('color','颜色'),('material','材质'),('model_number','型号')]
def text(row,key):
    values=row.get(key) or []
    for lang in ('zh_CN','en_US'):
        for item in values:
            if item.get('language_tag')==lang and item.get('value'):return str(item['value']).strip()
    return ''
def fetch(key,limit):
    CACHE.mkdir(parents=True,exist_ok=True);p=CACHE/key.split('/')[-1]
    if p.exists() and 0<p.stat().st_size<=limit:return p
    tmp=p.with_suffix(p.suffix+'.part')
    with urllib.request.urlopen(BASE+key,timeout=60) as r,tmp.open('wb') as f:
        size=0
        while block:=r.read(65536):
            size+=len(block)
            if size>limit:raise ValueError('Download byte budget exceeded')
            f.write(block)
    tmp.replace(p);return p
def literal(value):
    if isinstance(value,(int,float)):return str(value)
    return "'"+str(value).replace('\\','\\\\').replace("'","''").replace('\x00','').replace('\r',' ').replace('\n',' ')+"'"
def insert(table,row,keys):
    condition=' AND '.join(f'{k}={literal(row[k])}' for k in keys)
    return f"INSERT INTO {table} ({','.join(row)}) SELECT {','.join(map(literal,row.values()))} WHERE NOT EXISTS (SELECT 1 FROM {table} WHERE {condition});"
def adapt(raw,ordinal):
    kind=raw['product_type'][0]['value'];category,name,base=GROUPS[kind];asin=raw['item_id']
    if not re.fullmatch('[A-Z0-9]{10}',asin):raise ValueError('Unexpected source ID')
    pid='ABO'+asin;attrs={label:v for field,label in FIELDS if (v:=text(raw,field)) and len(v)<=100}
    attrs['规格']='默认测试规格'
    ids=[f'AV{ordinal:03}{i:02}' for i in range(len(attrs))];joined='-'.join(ids)
    item=dict(productId=pid,sourceItemId=asin,source=SOURCE,license=LICENSE,sourceTitle=text(raw,'item_name'),
        name=('[公开样本] '+name+' · '+text(raw,'item_name'))[:200],categoryId=category,category=name,
        attributes=attrs,valueIds=ids,skuHash=hashlib.md5(joined.encode()).hexdigest(),valueIdsJoined=joined,
        testPrice=f'{base+(ordinal%4)*50}.00',testStock=0 if ordinal%10==0 else 8+ordinal%5,
        sourcePrice=None,sourceStock=None,imageId=raw['main_image_id'],raw=raw)
    reviews=json.loads((OUT/'curation.json').read_text(encoding='utf8')) if (OUT/'curation.json').exists() else {}
    if asin in reviews:
        review=reviews[asin];item.update(name='[公开样本] '+review['category']+' · '+review['title'],category=review['category'],categoryId=review['categoryId'],curation='Display title normalized; original raw record preserved')
    return item
def build_sql(rows):
    sql=['-- ABO source fields CC BY 4.0; generated default SKU, CNY price and inventory are LOCAL TEST VALUES.',
         'SET NAMES utf8mb4;','START TRANSACTION;',insert('simlect_product.sys_category',dict(category_id='A9100',category_name='公开商品样本',p_category_id='0',sort=910),['category_id'])]
    done=set();props=set()
    for r in rows:
        cat=r['categoryId']
        if cat not in done:sql.append(insert('simlect_product.sys_category',dict(category_id=cat,category_name=r['category'],p_category_id='A9100',sort=len(done)),['category_id']));done.add(cat)
        raw=r['raw'];bullets=[x['value'] for x in raw.get('bullet_point',[]) if x.get('language_tag') in ('zh_CN','en_US')][:4]
        description='\n'.join(['真实商品资料：Amazon Berkeley Objects (ABO)，署名 Amazon.com；CC BY 4.0。',f'来源商品ID：{r["sourceItemId"]}；原始名称：{r["sourceTitle"]}',
            '本地演示：页面价格为人民币测试价，库存为测试库存，默认SKU由本项目建立；均非来源商家的真实报价或库存。',
            '原始商品说明（如有）：',text(raw,'product_description'),*bullets,'资料来源：'+SOURCE,'许可：'+LICENSE])
        description=''.join(f'<p>{html.escape(line)}</p>' for line in description.splitlines() if line)
        sql.append(insert('simlect_product.product_info',dict(product_id=r['productId'],product_name=r['name'],product_desc=description,cover='public-catalog/'+r['productId']+'.jpg',category_id=cat,p_category_id='A9100',status=1,min_price=r['testPrice'],max_price=r['testPrice'],total_sale=0,commend_type=1),['product_id']))
        for i,((name,value),vid) in enumerate(zip(r['attributes'].items(),r['valueIds'])):
            prop='AP'+cat[1:]+str(('品牌','颜色','材质','型号','规格').index(name))
            if prop not in props:sql.append(insert('simlect_product.sys_product_property',dict(property_id=prop,property_name=name,p_category_id='A9100',category_id=cat,property_sort=i,cover_type=0),['property_id']));props.add(prop)
            sql.append(insert('simlect_product.product_property_value',dict(product_id=r['productId'],property_id=prop,property_name=name,property_sort=i,cover_type=0,property_value_id=vid,property_value=value,sort=1),['product_id','property_value_id']))
        sql.append(insert('simlect_product.product_sku',dict(product_id=r['productId'],property_value_id_hash=r['skuHash'],property_value_ids=r['valueIdsJoined'],price=r['testPrice'],sort=1),['product_id','property_value_id_hash']))
        sql.append(insert('simlect_stock.sku_stock',dict(product_id=r['productId'],property_value_id_hash=r['skuHash'],stock=r['testStock']),['product_id','property_value_id_hash']))
    return '\n'.join(sql+['COMMIT;',''])
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--upload-root',required=True);args=parser.parse_args()
    rows=[];seen=set();counts={}
    for shard in range(3):
        p=fetch(f'listings/metadata/listings_{shard}.json.gz',8_000_000)
        with gzip.open(p,'rt',encoding='utf8') as f:
            for line in f:
                raw=json.loads(line);kind=raw.get('product_type',[{}])[0].get('value')
                if kind not in GROUPS or not text(raw,'item_name') or not raw.get('main_image_id') or raw['item_id'] in seen:continue
                cat=GROUPS[kind][0]
                if counts.get(cat,0)>=10:continue
                seen.add(raw['item_id']);counts[cat]=counts.get(cat,0)+1;rows.append(adapt(raw,len(rows)+1))
                if len(rows)==50:break
        if len(rows)==50:break
    if len(rows)!=50:raise RuntimeError(f'Only {len(rows)} eligible products; refusing partial seed')
    need={r['imageId'] for r in rows};paths={}
    with gzip.open(fetch('images/metadata/images.csv.gz',8_000_000),'rt',encoding='utf8',newline='') as f:
        for r in csv.DictReader(f):
            if r['image_id'] in need:paths[r['image_id']]=r['path']
            if len(paths)==len(need):break
    images=OUT/'images';images.mkdir(parents=True,exist_ok=True)
    def image(row):
        path=paths[row['imageId']]
        if not re.fullmatch(r'[a-zA-Z0-9/_.-]+',path) or '..' in path:raise ValueError('Unsafe image key')
        p=images/(row['productId']+'.jpg');url=BASE+'images/small/'+path
        if not p.exists():
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(url,timeout=45) as response:data=response.read(200_001)
                    break
                except (OSError,urllib.error.URLError):
                    if attempt==2:raise
                    time.sleep(attempt+1)
            if len(data)>200_000 or not data.startswith(b'\xff\xd8'):raise ValueError('Image byte/type budget failed')
            p.write_bytes(data)
        row['imageSource']=url;row['imageSha256']=hashlib.sha256(p.read_bytes()).hexdigest()
        return p.stat().st_size
    with ThreadPoolExecutor(max_workers=3) as pool:sizes=list(pool.map(image,rows))
    target=Path(args.upload_root).resolve()/'file'/'public-catalog';target.mkdir(parents=True,exist_ok=True)
    for r in rows:shutil.copyfile(images/(r['productId']+'.jpg'),target/(r['productId']+'.jpg'))
    fetch('LICENSE-CC-BY-4.0.txt',100_000);shutil.copyfile(CACHE/'LICENSE-CC-BY-4.0.txt',OUT/'LICENSE-CC-BY-4.0.txt')
    (OUT/'products.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf8')
    (ROOT/'sql/24_public_catalog.sql').write_text(build_sql(rows),encoding='utf8')
    counts={cat:sum(r['categoryId']==cat for r in rows) for cat in sorted({r['categoryId'] for r in rows})}
    manifest=dict(dataset='Amazon Berkeley Objects',attribution='Amazon.com; ABO dataset authors (see ATTRIBUTION.md)',license=LICENSE,source=SOURCE,products=len(rows),categories=counts,imageBytes=sum(sizes),sqlSha256=hashlib.sha256((ROOT/'sql/24_public_catalog.sql').read_bytes()).hexdigest(),changes=['Selected 50 records','Added Chinese category labels','Escaped source descriptions as HTML','Generated default test SKU and CNY test price/inventory','Downloaded existing 256px source images'])
    if (ROOT/'sql/25_public_catalog_curation.sql').exists():manifest['curationSqlSha256']=hashlib.sha256((ROOT/'sql/25_public_catalog_curation.sql').read_bytes()).hexdigest()
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(manifest,ensure_ascii=False))
if __name__=='__main__':main()

