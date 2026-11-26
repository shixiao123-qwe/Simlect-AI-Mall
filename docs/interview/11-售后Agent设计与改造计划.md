# 售后 Agent v1：源码审查与实施计划

审查日期：2026-11-02。工作区外层和内层均无 Git 仓库，无法建立分支或恢复原始 diff；保留全部已有修改。扫描排除了 target、node_modules、.venv、dist、日志和 Python 缓存；目录清单约 1677 个文件。本次是全目录索引加售后相关代码定向审查，不声称逐行审计每个文件。

## 已有能力及证据

- Python LangGraph：`Simlect-backend/Simlect-agent/app/graph/{builder,nodes,state}.py`，复用现有 entry/context/agent_loop/finalize，不新增服务或多 Agent 框架。
- MCP：`app/mcp_server/server.py` 已有 QUERY_ORDERS、QUERY_LOGISTICS、PROPOSE_REFUND。`app/services/mcp_tools_service.py` 已校验订单归属并创建退款提案。
- 确认：`app/services/pending_action_service.py#create_pending/confirm` 已有用户归属、Redis TTL、锁及 CAS；`action_execute_service.py#execute` 确认后用用户 token 调 Java。前端 `src/components/agent/AgentConfirmCard.vue` 已支持通用确认卡。
- Java Order：`controller/internal/OrderAgentInternalController.java` 有 listOrders/getOrder/getOrderItem/listOrderItems/getLogistics。`controller/OrderController.java` 有用户退款等写接口。`biz/impl/OrderInfoServiceImpl.java` 已有退款、幂等和库存补偿，保持不动。
- Stock：`Simlect-stock` 有锁库存及幂等增减。售后申请阶段不调用它，也不改支付流程。
- RAG：`app/rag/retriever.py#search_faq` 依赖向量；本地已关闭向量，所以不能依赖其回答政策。增加本地版本化政策片段检索，明确演示规则，命中后直接引用，未知问题不生成承诺。
- 缺失：未找到 after_sale_request/after_sale_log、独立申请资格与提交接口；旧 REFUND 把退款提案和直接资金执行绑定，不适合“申请待审核”。

## 确定改造范围（先计划后编码）

1. Order 增加最小 DTO、Service、Mapper、用户 Controller 和内部只读资格 Controller；新增独立 SQL 建表迁移，绝不清库。用户身份从登录上下文取得；内部读使用现有内部令牌和显式归属校验。
2. 资格由 Java 根据订单、明细及申请类型判断；申请阶段只写申请及日志，不调用 Pay/Stock。确认前后都检查资格。请求 ID 幂等，数据库唯一约束防并发重复。
3. MCP 复用 QUERY_ORDERS/QUERY_LOGISTICS（对应 GET_USER_ORDERS/GET_LOGISTICS_INFO，不重复注册别名），新增 GET_ORDER_DETAIL、QUERY_AFTER_SALE_POLICY、CHECK_AFTER_SALE_ELIGIBILITY、CREATE_AFTER_SALE_REQUEST。最后一个仅生成确认卡，不提交 Java 写操作。
4. LangGraph 在导购分支前增加有界售后路由；咨询只读，明确申请且有订单项与原因才创建提案。缺参追问，用户按钮确认后才调用用户 Java 接口。售后优先，不让购物记忆劫持。
5. 新增本地政策片段、测试、架构/链路/面试文档。默认不执行生产迁移，不自动发起真实退款。

## v1 边界及状态

INIT 是 Redis 提案阶段（未落业务表）；用户确认后落库 PENDING_REVIEW。APPROVED/REJECTED/COMPLETED 是预留审核及履约状态，本次不自动审批、不把待审核当退款成功。没有审核后台和退货仓库流程，不能宣称完整售后闭环。支持 refund/return_refund/exchange 类型；状态资格规则仅为演示提交资格，不替代商家正式政策、签收时间或人工审核。

## 验证计划

10～20 个场景覆盖意图、政策证据、越权、状态、退款明细、重复请求、确认前不写、确认后 Java 写、超时及未知政策；Java Mockito/校验测试与 Python 离线测试先执行，再回归导购与原有订单流程。真实资金接口不参与测试。
