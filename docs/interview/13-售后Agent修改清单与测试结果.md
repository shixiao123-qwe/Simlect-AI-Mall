# 售后 Agent v1：修改清单与测试结果

2026-11-02。工作区没有 Git 元数据，下表记录本次操作涉及文件，不是推断的原始项目 Git diff。未改依赖版本、生产配置、密钥、原支付/库存业务流程。

## 文件清单

| 文件（相对于项目根目录） | 操作 | 作用 |
|---|---|---|
| [Simlect-backend/Simlect-order/app/src/main/java/com/simlect/entity/dto/AfterSaleDTO.java](../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/entity/dto/AfterSaleDTO.java) | 新增 | Check/Create参数：限制类型、长度和请求ID，不接受金额或状态 |
| [Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/AfterSaleService.java](../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/AfterSaleService.java) | 新增 | 本人归属、资格矩阵、锁内重查、请求幂等和申请/日志事务 |
| [Simlect-backend/Simlect-order/app/src/main/java/com/simlect/mappers/AfterSaleMapper.java](../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/mappers/AfterSaleMapper.java) | 新增 | MyBatis锁查询、申请/日志写入及本人申请列表 |
| [Simlect-backend/Simlect-order/app/src/main/java/com/simlect/controller/AfterSaleController.java](../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/controller/AfterSaleController.java) | 新增 | 用户登录态创建申请、查询本人最近申请 |
| [Simlect-backend/Simlect-order/app/src/main/java/com/simlect/controller/internal/AfterSaleInternalController.java](../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/controller/internal/AfterSaleInternalController.java) | 新增 | 内部令牌保护的只读资格接口 |
| [Simlect-backend/Simlect-order/app/src/test/java/com/simlect/biz/AfterSaleServiceTest.java](../../Simlect-backend/Simlect-order/app/src/test/java/com/simlect/biz/AfterSaleServiceTest.java) | 新增 | 19项资格、权限、幂等及锁后状态测试 |
| [Simlect-backend/Simlect-order/app/src/test/java/com/simlect/controller/AfterSaleControllerTest.java](../../Simlect-backend/Simlect-order/app/src/test/java/com/simlect/controller/AfterSaleControllerTest.java) | 新增 | 3项HTTP参数与服务器身份传递测试 |
| [Simlect-backend/Simlect-order/app/src/test/java/com/simlect/biz/AfterSaleMapperMySqlTest.java](../../Simlect-backend/Simlect-order/app/src/test/java/com/simlect/biz/AfterSaleMapperMySqlTest.java) | 新增 | 1项显式启用的真实MySQL映射/唯一索引/日志测试，事务回滚 |
| [sql/22_after_sale.sql](../../sql/22_after_sale.sql) | 新增 | 两个增量售后表及活跃申请唯一索引，无清理语句 |
| [deploy/import-after-sale.ps1](../../deploy/import-after-sale.ps1) | 新增 | 读取已有本地配置并执行建表，不打印密钥 |
| [Simlect-backend/Simlect-agent/knowledge/after_sale_policy.json](../../Simlect-backend/Simlect-agent/knowledge/after_sale_policy.json) | 新增 | 4个带版本、来源和适用范围的演示政策片段 |
| [Simlect-backend/Simlect-agent/app/services/after_sale_policy.py](../../Simlect-backend/Simlect-agent/app/services/after_sale_policy.py) | 新增 | 关键词检索原文；未命中或不可用时不猜测 |
| [Simlect-backend/Simlect-agent/app/services/after_sale_service.py](../../Simlect-backend/Simlect-agent/app/services/after_sale_service.py) | 新增 | HTTP读取订单与资格；创建Redis申请提案，拒绝不合格/畸形结果 |
| [Simlect-backend/Simlect-agent/app/services/after_sale_turn.py](../../Simlect-backend/Simlect-agent/app/services/after_sale_turn.py) | 新增 | 中文售后意图、缺参追问、多轮原因补全及工具编排 |
| [Simlect-backend/Simlect-agent/app/mcp_server/server.py](../../Simlect-backend/Simlect-agent/app/mcp_server/server.py) | 修改 | 注册4个新售后工具，订单/物流两个已有工具继续复用 |
| [Simlect-backend/Simlect-agent/app/harness/guardrails/tool_guard.py](../../Simlect-backend/Simlect-agent/app/harness/guardrails/tool_guard.py) | 修改 | 售后只读工具和提案工具白名单 |
| [Simlect-backend/Simlect-agent/app/services/mcp_tool_router.py](../../Simlect-backend/Simlect-agent/app/services/mcp_tool_router.py) | 修改 | 新工具参数映射及会话userId强制覆盖 |
| [Simlect-backend/Simlect-agent/app/services/action_execute_service.py](../../Simlect-backend/Simlect-agent/app/services/action_execute_service.py) | 修改 | 用户确认后用登录token调用Java申请接口，不调用退款接口 |
| [Simlect-backend/Simlect-agent/app/utils/biz_payload.py](../../Simlect-backend/Simlect-agent/app/utils/biz_payload.py) | 修改 | 申请确认按钮、风险文案、申请类型/原因详情 |
| [Simlect-backend/Simlect-agent/app/graph/nodes.py](../../Simlect-backend/Simlect-agent/app/graph/nodes.py) | 修改 | 在购物之前执行售后分支，保留取消检查及统一收束 |
| [Simlect-backend/Simlect-agent/app/graph/state.py](../../Simlect-backend/Simlect-agent/app/graph/state.py) | 修改 | 售后处理标记和工具审计状态 |
| [Simlect-backend/Simlect-agent/app/services/agent_runtime.py](../../Simlect-backend/Simlect-agent/app/services/agent_runtime.py) | 修改 | 保留服务端确认卡；售后答复不回填商品推荐 |
| [Simlect-front/Simlect-web/src/components/agent/AgentConfirmCard.vue](../../Simlect-front/Simlect-web/src/components/agent/AgentConfirmCard.vue) | 修改 | 售后卡确认完成显示提交申请，复用原有卡片 |
| [evals/test_after_sale.py](../../evals/test_after_sale.py) | 新增 | 33项离线售后契约测试，不执行真实退款 |
| [docs/interview/11-售后Agent设计与改造计划.md](../../docs/interview/11-售后Agent设计与改造计划.md) | 新增 | 修改前只读审查、既有能力和改造边界 |
| [docs/interview/12-售后Agent实现与面试笔记.md](../../docs/interview/12-售后Agent实现与面试笔记.md) | 新增 | 架构、调用链、18场景、演示说明及10组面试讲解 |
| [docs/interview/13-售后Agent修改清单与测试结果.md](../../docs/interview/13-售后Agent修改清单与测试结果.md) | 新增 | 本文件，逐文件说明与实际报告 |
| [docs/interview/09-阶段验收与未解决项.md](../../docs/interview/09-阶段验收与未解决项.md) | 修改 | 追加本次独立售后阶段入口，不覆盖历史验收 |
| [evals/reports/after-sale-python.xml](../../evals/reports/after-sale-python.xml) | 生成 | 最终Python真实测试报告 |
| [evals/reports/after-sale-summary.json](../../evals/reports/after-sale-summary.json) | 生成 | 从Python及Java测试XML汇总，不虚构指标 |

## 实际验证

- Python最终 **100项通过**：新增售后33项 + 导购57项 + 原Graph4项 + 原pending确认6项。所有LLM/HTTP/Redis写边界为模拟，不能声称真实聊天端到端。
- Java最终 **150项通过**：新增Service19项、Controller3项、真实MySQL Mapper1项；原订单108项、原超时关单监听19项。0失败、0错误、0跳过。
- MySQL集成测试显式设置 SIMLECT_AFTER_SALE_DB_TEST=true；使用本地已有数据库和随机夹具，所有夹具在同一事务中回滚，结束后检查订单不存在。验证字段映射、申请、日志、请求重放、归属及唯一索引；它不是Spring分布式事务或真实并发压测。
- 增量SQL已成功创建本地售后表。没有导入模拟售后业务记录、没有清空数据库。
- Vue确认卡SFC脚本/模板编译通过；迁移PowerShell AST语法通过。未运行整站前端build或新版网页完整点击验收。
- 最初沙箱测试因导入/子进程权限停滞，中止后在允许的沙箱外重跑。首轮Python测试桩缺Redis client、Java Mockito Map默认空值导致失败，修正测试桩后得到上述最终结果。保留实际结果，不把首轮失败写成全程一次通过。

原始证据：`evals/reports/after-sale-python.xml`、`evals/reports/after-sale-summary.json`、`logs/after-sale-maven.log` 和 Order 的 target/surefire-reports 对应五个测试类XML。日志和target是本机产物，可重新执行生成。只有pytest原有loop scope弃用提示，无失败；未为消除提示升级依赖。

## 复现

在项目根目录运行Python：

```powershell
.\Simlect-backend\Simlect-agent\.venv\Scripts\python.exe -m pytest evals/test_after_sale.py evals/test_shopping.py Simlect-backend/Simlect-agent/tests/test_agent_graph.py Simlect-backend/Simlect-agent/tests/integration/test_pending_confirm.py -q -p no:cacheprovider --junitxml=evals/reports/after-sale-python.xml
```

Java普通回归（不启用真实数据库测试）：

```powershell
$env:MAVEN_OPTS='-Xms64m -Xmx512m -XX:ActiveProcessorCount=2'
mvn.cmd -o -f Simlect-backend/pom.xml -pl Simlect-order/app -am test '-Dtest=AfterSaleServiceTest,AfterSaleControllerTest,OrderInfoServiceImplTest,RabbitMQPayOrderDeadListenerComponentTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2' --no-transfer-progress
```

真实Mapper测试必须先建表，显式设 SIMLECT_AFTER_SALE_DB_TEST=true、MYSQL_PASSWORD（本地配置值）和可选MYSQL_USER，再把 AfterSaleMapperMySqlTest 加入 -Dtest。只对本机演示库执行，不让测试自动读取生产密钥。Maven离线缓存缺依赖时会失败，应说明原因，不能写成已通过。

## 尚未完成及建议顺序

1. 停止旧启动器并 -Rebuild 后，按12文档在网页真实点击确认/取消；当前未替用户停止运行中的服务。
2. 当前申请到PENDING_REVIEW结束。后续增加管理员审核、CAS状态转换、审计列表，再按商家政策设计退货/验货/资金衔接，不用SQL直接改成退款成功。
3. 增加正式政策和签收时间、商品例外，才可扩展日期窗口资格。当前不能承诺七天无理由、运费或到账时间。
4. 多轮自然语言指代仍有限；当前中文规则可解释，但不宣称通用意图识别准确率。
5. 尚未验证真实支付渠道、长时间稳定性、负载、安全渗透和生产故障恢复；没有基线或性能提升数据。

可写入简历的真实成果：在既有LangGraph/MCP电商项目中新增轻量售后工作流，复用用户确认机制，通过Java实现订单归属、状态资格、请求幂等和事务审计，使用版本化政策片段回答咨询，并完成离线契约测试、订单回归及本地MySQL回滚验证。不能写成独立搭建整个商城或全自动退款系统。


## 2026-11-02 网页验收后修正：错误订单项反馈

现场问题：把真实订单号中的小写l复制为大写I，Java正确拒绝不存在的订单项，但Python把预期业务拒绝抛到MCP协议层，网页只显示“工具执行失败”。修正 `after_sale_service.py#eligibility`：只把已知、安全的Java业务拒绝映射为eligible=false，未知异常、畸形响应和超时仍失败关闭，不把内部异常原样显示。

新增3项测试：不存在/越权的可读拒绝；拒绝不创建pending；未知Java错误不转为可见业务文案。售后36项与导购57项共93项通过，见 `evals/reports/after-sale-denial.xml`。这次回归范围与上面的100项不同，不直接累加为全套总数。

只重启已核实所有权的MCP进程，实际调用MCP→Java的CHECK_AFTER_SALE_ELIGIBILITY两次：正确订单项返回“当前订单状态不支持此申请类型”（订单已超时关闭）；错误订单项返回“订单项不存在或无权访问”，均不再是协议执行失败。没有修改订单状态、创建申请或执行退款。


## 2026-11-02 确认卡输出护栏修复

网页实测已成功模拟付款、查询资格和补齐原因，但最终只有提案文案没有按钮。根因是 `app/harness/guardrails/output_guard.py#validate_no_false_completion` 只按PROPOSE_前缀承认提案，删除了CREATE_AFTER_SALE_REQUEST真实返回的act令牌。

改为复用tool_guard.WRITE_TOOLS白名单识别合法提案，未知PROPOSE名称不能获得豁免；售后申请工具也不能声称资金退款完成。新增测试覆盖合法/非法工具令牌、虚假完成声明，以及真正的 finalize_node -> 输出护栏 -> resolve_action_confirm -> JSON payload -> DONE/持久化链路（不再只单测mock掉resolver的分支）。

本次售后、导购、护栏、pending确认共111项通过，原始证据 `evals/reports/after-sale-confirm-card.xml`。只重启Agent，健康通过；只读使用本机演示用户已有1条Redis提案，经真实护栏及resolver成功生成ACTION_CONFIRM，按钮文字“确认提交申请”、状态0。没有替用户提交申请或改聊天记录；旧纯文本消息不会自动变化，需要重新发送完整申请生成新卡。
