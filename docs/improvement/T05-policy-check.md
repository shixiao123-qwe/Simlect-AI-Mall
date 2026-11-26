# T05 售后政策轻量检查

状态：无需修改。

## 1. 原问题或当前状态

Java AfterSaleService.POLICY_VERSION 与 Python policy JSON 均为 demo-v3；AfterSalePolicyRules保留。Java明确传Receipt.unavailable()，没有用订单创建时间代替签收。

## 2. 原因

政策没有可信签收、正式期限、运费、到账资料。现有POLICY_MISSING和APPLICATION_ONLY已经明确区分提交资格与批准结果。

## 3. 原执行流程

用户询问政策 → Python版本化原文与限制；申请资格 → Java规则计算器 → 缺失资料转人工。

## 4. 修改后流程与文件

流程保留，不新增七天、运费或到账承诺；无业务文件修改。

## 5. 技术与复用

保留规则计算器、显式资格范围与版本化资料；跨语言版本一致性定向断言。

## 6. 一致性保障

缺失签收不推断时间，缺失政策不自动补默认承诺。

## 7. 异常测试

Java14项规则测试及Python版本/签收契约检查通过，含既有规则边界。

## 8. 面试提问与回答

问：能否用订单完成时间估算签收？答：不能，必须有可靠签收事件，否则转人工。

## 9. 剩余问题与延期

尚无正式商家政策和签收资料，向量政策RAG未验证，保持延期。


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
