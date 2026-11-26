# T06 Agent会话轻量检查

状态：仍有边界问题，已修主会话与售后上下文。

## 1. 原问题或当前状态

agent_service以后台任务启动同一用户的图，原先没有按用户互斥；session_memory_service整段保存state，重叠任务可能覆盖。新售后订单没有明细时还保留旧afterSaleContext。

## 2. 原因

全局任务计数只是背压，不是会话锁。messageId独立checkpoint不能防止共同用户state覆盖。

## 3. 原执行流程

多个后台任务 → 并行读同一用户state → 各自覆盖保存；明确新订单后下一句原因可能继承旧订单项。

## 4. 修改后流程与文件

AgentEngine在图前使用Redis owner锁，锁忙拒绝；150秒图超时小于180秒租期。锁内比较用户最近messageId，拒绝更早请求；允许相同messageId恢复原checkpoint。明确新订单无明细时清除旧售后上下文。

## 5. 技术与复用

复用redis.asyncio原有锁、原Graph入口与原存储，无表变更。导购当前轮SKU保护沿用T09。

## 6. 一致性保障

图内会话读写串行；释放锁校验owner，不删除其他实例租约；同用户多标签页共用用户锁。

## 7. 异常测试

锁忙不运行图、旧消息拒绝、同消息恢复、新消息执行、切新售后订单清旧上下文及原导购轮次测试通过。

## 8. 面试提问与回答

问：为什么线程ID不同仍会串扰？答：checkpoint按message分离，但用户记忆是共享记录，需要在整轮入口协调。

## 9. 剩余问题与延期

agent_service.send_message的商品咨询快照预处理仍位于图锁之前，多标签页咨询态边界尚未完全覆盖；未重构该存储流程。未做真实多实例Redis故障注入。


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
