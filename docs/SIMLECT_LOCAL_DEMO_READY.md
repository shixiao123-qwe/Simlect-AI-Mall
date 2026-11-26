# 本地演示服务启动记录

2026-11-04。商城：http://127.0.0.1:6001/；管理后台：http://127.0.0.1:6002/admin/。

实际验证：两个前端HTTP200；网关UP；Agent健康检查Redis/MySQL/ES/Java均true且status=ok；MCP status=ok。真实配置模型能够将“耳机预算300以内”解析为品类耳机、预算上限300。网关真实搜索keyWords=耳机返回5件商品，分类接口success。本地库有55件上架商品、50个有库存SKU。

这些是服务就绪与只读接口检查，不等于已经替用户完成下单、支付、售后或退款。本轮由用户自行操作三个场景：

1. 商城登录 → 搜索耳机 → 选择有库存SKU → 购物车/下单 → 本地模拟付款 → 我的订单核对状态。
2. 智能客服输入“耳机预算300以内” → 查看真实商品 → “预算改成500” → “对比第1款和第2款”。
3. 智能客服查询本人订单/订单项 → 明确退款或退货退款与原因 → 用户确认卡提交 → 后台人工审核 → 按类型登记演示退货、验货 → 模拟退款 → 核对用户售后进度。

保持现有SIMLECT_PAY_LOCAL_MOCK_ENABLED、SIMLECT_PAY_DEMO_CONFIRM_ENABLED、SIMLECT_AFTER_SALE_REFUND_ENABLED=true；VECTOR_SEARCH_ENABLED=false，无Embedding Key。不得将关键词演示描述成真实向量RAG联调。本地启动器保持原SEATA_ENABLED=false，不能据此宣称验证了Seata跨库事务。

管理员凭据沿用deploy/.env.local，不在记录中抄写。用户注册若需演示邮件验证码，可查看本次user.out.log，勿将验证码或完整日志上传。

启动日志：logs/demo-startup.log。服务详细日志：logs/local-stack/20261104-202622-029/。服务保持运行；停止使用项目原stop-local.cmd。

环境修复：Docker损坏临时套接字以改名方式备份，没有删除容器、镜像或数据卷。在E盘新增分页文件初始8GB、最大16GB，已生效无需重启；D盘原配置未改。原分页配置记录logs/pagefile-before.json。为维持原内存安全阈值，仅对本次校验归属的Java进程做工作集换页，临时辅助进程已停止，未关闭用户应用。

数据库启动准备：先备份backups/mysql/20261104-201425-156/simlect-databases.sql，再将现有sql/26_outbox_lease.sql应用于六个已有Outbox库，将sql/28_mq_consumer_inbox.sql应用于order/coupon/user/search；未重导或重置业务数据。备份含私有本地数据，仅本地保存。
