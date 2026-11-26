# 二次完善开发记录

每完成一个 T01～T10 阶段，补充一份对应开发记录。仅在阶段实际完成后填写实现和测试结果，尚未运行的测试明确标注。

记录应包含原问题、原因、修改前调用链、修改后调用链、设计模式与技术、一致性保障、异常测试、面试提问及回答。附修改文件、实际测试命令与数量、剩余验证边界。业务代码存在、Mock 验证、真实外部联调分别表述。

| 阶段 | 开发记录 | 状态 |
|---|---|---|
| T01 退款与售后入口安全 | [T01-refund-security.md](T01-refund-security.md) | 已完成，已补开发记录 |
| T02 Outbox 恢复 | [T02-outbox-recovery.md](T02-outbox-recovery.md) | 已完成，单元及隔离集成测试通过；业务库待迁移部署 |
| T03 Inbox 幂等 | [T03-inbox-idempotency.md](T03-inbox-idempotency.md) | 已完成，本地事务与隔离 MySQL/RabbitMQ 测试通过；业务库待迁移部署 |
| T04 Agent 动作恢复 | [T04-agent-action-recovery.md](T04-agent-action-recovery.md) | 已完成，Python 43、Java 33、确认卡 SSR 7 项定向验证通过；真实端到端联调未执行 |
| T07 支付宝回调严格校验 | [T07-alipay-callback-security.md](T07-alipay-callback-security.md) | 已完成，275 项 Java 定向测试通过；真实商户配置与渠道联调待部署核实 |
| T09 智能导购优化 | [T09-shopping-agent-optimization.md](T09-shopping-agent-optimization.md) | 已完成，Python 76、Java 20项定向测试通过；真实LLM/向量/MCP联调未验证 |
| T05、T06、T08、T10 轻量检查 | [轻量检查汇总](REMAINING_LIGHTWEIGHT_CHECK.md) | 已检查并局部修复；Python27/Java16通过，T06/T10仍有明确延期边界 |

后续阶段完成时同步更新此索引，并在开发记录中注明与已完成阶段的依赖及复用关系。
