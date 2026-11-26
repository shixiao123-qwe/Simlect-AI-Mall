import importlib.util,json,hashlib,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('adapter',ROOT/'deploy/prepare-public-catalog.py');adapter=importlib.util.module_from_spec(spec);spec.loader.exec_module(adapter)
class PublicCatalogTests(unittest.TestCase):
 def setUp(self):self.rows=json.loads((ROOT/'data/public-catalog/products.json').read_text(encoding='utf8'))
 def test_fifty_unique_traceable_real_products(self):
  self.assertEqual(50,len(self.rows));self.assertEqual(50,len({r['productId'] for r in self.rows}))
  for r in self.rows:
   self.assertEqual(r['sourceItemId'],r['raw']['item_id']);self.assertEqual(r['sourceTitle'],adapter.text(r['raw'],'item_name'));self.assertTrue(r['license'].endswith('/by/4.0/'))
 def test_no_source_prices_stock_or_condition_are_fabricated(self):
  for r in self.rows:
   self.assertIsNone(r['sourcePrice']);self.assertIsNone(r['sourceStock']);self.assertNotIn('成色',r['attributes'])
   for field,label in adapter.FIELDS:
    if label in r['attributes']:self.assertEqual(adapter.text(r['raw'],field),r['attributes'][label])
 def test_sku_keys_and_schema_lengths(self):
  for r in self.rows:
   self.assertLessEqual(len(r['productId']),15);self.assertLessEqual(len(r['name']),200)
   self.assertEqual(r['skuHash'],hashlib.md5(r['valueIdsJoined'].encode()).hexdigest());self.assertEqual(len(r['valueIds']),len(r['attributes']))
 def test_images_match_recorded_digest_and_byte_budget(self):
  total=0
  for r in self.rows:
   data=(ROOT/'data/public-catalog/images'/f"{r['productId']}.jpg").read_bytes();total+=len(data)
   self.assertTrue(data.startswith(b'\xff\xd8'));self.assertLessEqual(len(data),200000);self.assertEqual(hashlib.sha256(data).hexdigest(),r['imageSha256'])
  self.assertLessEqual(total,10000000)
 def test_sql_is_insert_only_with_transaction_and_escaped_external_text(self):
  sql=(ROOT/'sql/24_public_catalog.sql').read_text(encoding='utf8')
  self.assertEqual(sql,adapter.build_sql(self.rows));self.assertIn('START TRANSACTION;',sql);self.assertTrue(sql.endswith('COMMIT;\n'));self.assertNotIn('DELETE FROM',sql);self.assertNotIn('UPDATE ',sql);self.assertNotIn('simlect_order.',sql)
  self.assertEqual(adapter.literal("a'b\\c"),"'a''b\\\\c'")
if __name__=='__main__':unittest.main()
