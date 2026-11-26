# 剩余四方向轻量检查汇总

- T05：无需修改。版本demo-v3一致，缺失政策/签收转人工，无自动承诺。
- T06：主会话互斥与售后旧上下文已修；咨询态预处理仍有锁外并发边界，保留为问题。
- T08：已修产品删除ID、FAQ先删后写、Java/Python向量字段契约；真实向量联调未验证。
- T10：已收紧本地端口、补ignore、修健康假阳性；无Git历史可审计，Python/MCP TraceId与凭据部署治理仍延期。

修改生产文件：

- Simlect-backend/Simlect-agent/app/services/agent_engine.py
- Simlect-backend/Simlect-agent/app/services/after_sale_turn.py
- Simlect-backend/Simlect-agent/app/services/health_service.py
- Simlect-backend/Simlect-agent/app/rag/retriever.py
- Simlect-backend/Simlect-search/src/main/java/com/simlect/component/RabbitMQRagListenerComponent.java
- deploy/docker-compose.middleware.yml
- .gitignore

新增测试：Simlect-agent/tests/test_lightweight_remaining.py、Simlect-search/src/test/java/com/simlect/component/RagLightweightSafetyTest.java（模块均位于Simlect-backend）。Python27项、Java16项通过，无失败或跳过；相关日志及命令见各阶段记录。没有修改业务数据库、密钥、真实支付/退款配置或容器运行状态。

分项记录：[T05](T05-policy-check.md)、[T06](T06-session-check.md)、[T08](T08-rag-check.md)、[T10](T10-engineering-check.md)。

没有启动其他开发任务。
