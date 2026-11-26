# T08 RAG轻量检查

状态：已完成明确缺陷修复，真实联调未验证。

## 1. 原问题或当前状态

商品缺失时把product文档前缀传给metadata.productId删除条件，匹配不到真实productId；FAQ先删再新增，Embedding失败会丢旧知识；Python向量字段与Java不一致。

## 2. 原因

文档ID与业务ID混淆；FAQ采用破坏式更新；Python沿用content_vector/vector而Java实际SDK默认embedding。

## 3. 原执行流程

删除productp1匹配metadata.productId；FAQ delete再add；Python查询错误字段。

## 4. 修改后流程与文件

删除使用原productId；FAQ保留稳定文档ID直接add/upsert，生成新向量失败不先删除旧文档；Python查询embedding，去掉错误的404字段重试。

## 5. 技术与复用

保留商品先写成功再清旧文档、原Inbox、VectorStore和RRF；没有新建RAG系统。

## 6. 一致性保障

本地安装Spring AI1.1.2字节码核对embedding字段；Java索引默认simlect_vectorstore及1024维与Python设置对应。metadata.dataType/productId映射保留。RRF保留T09去重逻辑。

## 7. 异常测试

Java测试断言FAQ写失败不delete、商品删除使用真实ID；PythonMock断言embedding字段、product过滤和productId解析。原RRF测试随定向回归通过。

## 8. 面试提问与回答

问：数据库事务是否能回滚ES？答：不能。此处仅避免先删除造成的确定性丢失，重试依赖原Inbox与稳定ID，不宣称跨系统原子性。

## 9. 剩余问题与延期

未开启向量配置或调用真实Embedding/ES。尚未验证真实索引mapping和实际upsert故障窗口；无全量索引重建。


## 测试命令与共同验证边界

Python目录 `Simlect-backend/Simlect-agent`：
```powershell
$env:SIMLECT_INTERNAL_TOKEN='lightweight-isolated-test-token'
.venv/Scripts/python.exe -m pytest tests/test_lightweight_remaining.py tests/test_shopping_turn_safety.py -q -p no:cacheprovider -o asyncio_default_fixture_loop_scope=function
```
最终27通过、0失败、0跳过。日志：`logs/lightweight-python.log`。Java目录 `Simlect-backend`：
```powershell
mvn -o -pl Simlect-search,Simlect-order/app -am test '-Dtest=RagLightweightSafetyTest,AfterSalePolicyRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```
最终16通过（政策14、RAG2），0失败/错误/跳过，BUILD SUCCESS。日志：`logs/lightweight-java-final.log`。各记录引用同一轮结果，不能重复相加。所有依赖用Mock，未连接生产或真实支付；未创建数据库表、重建向量索引或重启容器。未执行全项目扫描/回归。
