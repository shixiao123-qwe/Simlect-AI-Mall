# T10 工程化轻量检查

状态：仍有问题，已修关键本地暴露与健康检查。

## 1. 原问题或当前状态

未发现项目及父目录Git仓库，无法验证提交历史。现有ignore漏部分密钥后缀；本地Compose端口默认绑定所有地址；HTTP健康检查将401/404等当作可用。

## 2. 原因

本地演示配置不应对公网开放；存活与依赖可用不是同一判断。

## 3. 原执行流程

中间件宿主端口无地址限制；健康检查使用status<500；Java网关探测/api/而非健康端点。

## 4. 修改后流程与文件

Compose十个端口映射仅绑定127.0.0.1，保留原编码，未运行部署；ignore增加key/pfx/jks/keystore/crt及application-local后缀。Java探测/actuator/health并要求200且status=UP，ES要求2xx，失败标degraded，核心Redis/MySQL失败仍unhealthy。

## 5. 技术与复用

保留已有启动模块表、JVM配置、Java TraceIdFilter/FeignTraceInterceptor，不引入监控框架。

## 6. 一致性保障

没有创建Git、清理历史、覆盖凭据或更换用户密码；ignore不等于撤回已泄露凭据。

## 7. 异常测试

健康检查200/401/404/503分支Mock测试通过；Compose十个端口绑定静态核对。启动脚本模块路径与当前目录对应，未执行启动。

## 8. 面试提问与回答

问：ignore能修复历史密钥泄露吗？答：不能，需有Git历史才能审计，确认泄露后另做轮换和历史治理。

## 9. 剩余问题与延期

Python/MCP缺少完整TraceId传递，本轮记录为延期。deploy/.env.local、演示Compose、示例和search配置中存在凭据类设置项，值不输出、未更改；无Git无法认定是否被提交，示例/占位也不等于真实泄露。生产隔离、密码轮换和真实依赖健康联调尚需部署阶段核实。


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
