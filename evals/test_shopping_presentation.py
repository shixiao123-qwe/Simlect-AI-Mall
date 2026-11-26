import json
from app.services.shopping_service import presentation_cards
from app.models.shopping import ShoppingRequirement
from app.utils.biz_payload import build_product_search_message

def test_verified_evidence_and_conditions_survive_frontend_wire():
 rows=[{'product_id':'p','product_name':'示例','sku_hash':'s','price':'199.00','stock':2,'attributes':{'颜色':'蓝'}}]
 cards=presentation_cards('[{"productId":"p","productName":"示例","minPrice":199}]',rows,ShoppingRequirement(category='厨具',max_price='300'),'recommend')
 payload=json.loads(build_product_search_message('无需从此文本推断价格',cards))
 card=payload['products'][0]
 assert card['verified']['price']=='199.00' and card['verified']['stock']==2
 assert card['verified']['attributes']=={'颜色':'蓝'}
 assert card['verified']['skuHash']=='s'
 assert card['shopping']['conditions']==['厨具','预算 ≤ ¥300.00']

def test_comparison_marks_unavailable_without_inventing_attributes():
 rows=[{'product_id':'p','product_name':'示例','sku_hash':'s','price':100,'stock':1,'attributes':{}}]
 cards=presentation_cards('[{"productId":"p","productName":"示例"}]',rows,ShoppingRequirement(),'compare',2)
 first=json.loads(cards)[0]
 assert first['shopping']['unavailableCount']==1
 assert first['verified']['attributes']=={}
 assert '参数' in first['shopping']['conclusion'] or '判断' in first['shopping']['conclusion']

def test_unknown_card_cannot_claim_java_verification():
 import pytest
 with pytest.raises(KeyError):presentation_cards('[{"productId":"unknown"}]',[],ShoppingRequirement(),'recommend')
