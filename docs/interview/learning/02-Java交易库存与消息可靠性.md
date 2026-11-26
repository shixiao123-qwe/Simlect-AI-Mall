# Java 交易、库存与消息可靠性：从一次下单讲到故障恢复

本章按 2026-11-25 的真实源码编写，只读核对代码，没有为编写教材启动服务或重新运行测试。阅读目标是把正常路径、并发路径和失败路径连起来，而不是记住几个注解。建议先通读前三节，再逐节做末尾练习。

先明确边界：项目主要使用 **MyBatis Mapper/XML 和自定义 BaseMapper**，不是看到 BaseMapper 就认定用了 MyBatis-Plus。本地启动器显式关闭 Seata；支付和退款演示不发生真实资金流转。Outbox、库存操作记录、状态机等已有基础不能都说成个人从零新增；后续的分页、缓存、租约和故障场景增强，应与原有骨架分别说明。已有改造记录见[数据分页与 AI 工具可靠性报告](../24-数据分页与AI工具可靠性改造报告.md)。本章不提供未经执行的并发量、吞吐量或性能提升数字。

## 1. 请求入口与业务事实：为什么客户端不能决定身份和价格

先看 [OrderController.postOrder](../../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/controller/OrderController.java)。Controller 负责接收请求、触发 DTO 校验、取得登录身份，把真正的下单工作交给 Service。它不负责拼装每张订单表，也不靠前端传来的 userId 决定订单归属。

```java
@PostMapping("/postOrder")
@GlobalInterceptor(checkLogin = true)
public ResponseVO postOrder(@Valid @RequestBody PostOrderDTO postOrderDTO){
    PayInfoDTO payInfoDTO = orderInfoService.postOrder(getTokenUserInfo().getUserId(), postOrderDTO);
    return getSuccessResponseVO(payInfoDTO);
}
```

第一行将 HTTP 请求映射到方法；第二行接入项目登录检查；第三行的 `@Valid` 检查请求结构，`postOrderDTO` 是用户的购买意图；第四行从登录上下文取 userId，`payInfoDTO` 保存下单产生的支付准备结果；最后统一包装响应。这里的“校验通过”只说明入口参数符合要求，不等于库存够、地址属于本人、SKU 存在。

[PostOrderDTO](../../../Simlect-backend/Simlect-order/api/src/main/java/com/simlect/api/dto/PostOrderDTO.java) 包含地址、支付方式、订单来源、商品列表和可选优惠券；明细使用 [ProductItem](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/entity/po/ProductItem.java)。Service 还会通过 User 查地址，通过 Product 查商品、属性及 SKU 快照。商品价格来自服务端 SKU，明细金额由价格乘购买数量计算。客户端显示价格、ES 索引价格和模型推荐文字都不能直接成为扣款金额。

SKU 是具体可售规格。同一款耳机的黑色和白色可以具有不同价格、库存。项目用 productId 与 propertyValueIdHash 关联具体库存，Product 保存商品及 SKU 事实，Stock 保存库存事实，Order 保存成交时的商品名称、属性和金额快照。因此商品以后改名，历史订单仍能展示当时买了什么。

异常时，非法数量、下架商品、无 SKU、地址不存在等应抛业务异常，不能用默认价格继续下单。替代方案是将商品与库存放在单体同一事务里，部署更简单；当前服务拆分带来职责清晰，也增加了调用失败和跨服务一致性成本。

**连续追问。** 问：前端已经登录，为什么 Service 还检查地址？答：登录只确认是谁，不能证明提交的地址 ID 属于本人。再问：金额为什么不信前端？答：请求可以被修改，服务端应重新计算。再问：有服务端校验就绝无漏洞吗？答：还需逐字段检查 ID、SKU、属性组合及归属的一致性，DTO 注解不能替代业务规则。

## 2. app、api、MyBatis 和代理：一个方法调用实际穿过哪些层

以 Stock 为例，[StockFeignClient](../../../Simlect-backend/Simlect-stock/api/src/main/java/com/simlect/api/StockFeignClient.java) 位于 api 模块，声明跨服务契约；[StockFeignSupport](../../../Simlect-backend/Simlect-stock/api/src/main/java/com/simlect/api/support/StockFeignSupport.java) 封装请求及响应拆包；app 中的 Controller、Service、Mapper 才执行业务。api 是其他服务依赖的 JAR，不是另一个需要启动的库存进程。

Order 调用 `stockFeignSupport.getAvailable`，Support 调 Feign 代理，代理发 HTTP 请求；库存侧 Controller 调 Service，Service 调 Mapper 代理；Mapper 将接口方法与 XML SQL 关联，再使用当前事务连接访问 MySQL。Feign 的 Java 方法看起来像本地调用，但可能发生网络超时；MyBatis Mapper 也不是普通对象，它由框架根据接口及映射生成实现。

[自定义 BaseMapper](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/mappers/BaseMapper.java) 声明了 selectList、selectCount、insert 等方法，具体 SQL 在 XML 中。例如 [SkuStockMapper.xml](../../../Simlect-backend/Simlect-stock/app/src/main/resources/com/simlect/mappers/SkuStockMapper.xml) 用 `#{...}` 绑定 productId、数量。绑定参数与把输入拼进 SQL 是两回事；项目部分排序使用 `${query.orderBy}`，因此必须经过服务端白名单或固定表达式构造，不能直接接收任意排序文本。

这层 Support 的价值是统一失败语义。例如远端返回错误时抛业务异常，Order 不应把“库存服务不可用”当成扣减成功。它同时让业务方法少写重复的响应结构判断。替代方案是直接注入 FeignClient，但错误转换容易散落；把调用全塞进 Controller 又会令业务逻辑难测。

**连续追问。** 问：Feign 如何知道服务在哪里？答：契约声明服务名，调用框架结合服务发现和负载均衡选择实例。再问：API 模块升级就等于服务升级吗？答：契约 JAR 和运行中的服务是两个交付物，仍需保证兼容。再问：Mapper 为什么能参与事务？答：正常 Spring 集成会复用事务绑定的数据库资源，不是每个 Mapper 都自行开启一个互不相干的连接。

## 3. 按真实代码读 postOrder：不要按注释背执行顺序

打开 [OrderInfoServiceImpl.postOrder](../../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/impl/OrderInfoServiceImpl.java)。方法较长，可以把它拆成“校验与快照、组装与优惠、写入与远程协作”三段。以下是当前方法正文的先后顺序，注释中的简图不能替代正文：

1. 校验支付方式、订单来源，调用 User 获取当前用户地址。
2. 收集商品 ID，批量取得 Product 快照，形成商品、属性和 SKU 的索引 Map。
3. 遍历购买项，验证上架状态、属性、SKU、购买数量，查询可售库存并计算明细金额。
4. 按 productId 组织子订单；每个 SKU 形成 orderItem。多个子订单共用一个 `unifiedPayOrderId`，便于一次合并付款。
5. 有优惠券时，经 Coupon 校验并预占，分摊优惠，插入订单与优惠券关联；这部分在下文库存 try/catch 之前。
6. 调 Stock `lockAndVerify`，再插入 order_info、order_item 和当前代码组装的物流信息。
7. 以负数数量远程执行幂等扣库存；购物车下单则再远程清理对应购物车项。
8. 汇总并规范支付金额，调用 Pay 获取支付信息及创建待支付记录。
9. 为子订单登记支付超时消息，返回 PayInfoDTO；本地事务真正提交发生在代理结束方法调用时。

为什么先组装 Map？这样按商品合并子订单，减少重复建立 OrderInfo；SKU 明细仍独立保存。为什么使用 BigDecimal？金额计算需要明确的小数语义，使用二进制浮点可能产生不可见误差。规范支付金额也不能替代审计优惠分摊、明细与总额是否一致。

尤其要观察 `stockChangeAttempted`。它在发扣库存 RPC 前置为 true，`stockDeducted` 则在 RPC 正常返回后置为 true。这两个变量表达“调用尝试过”和“已确认返回”的区别。远端可能成功但响应丢失，只看第二个变量会漏掉需要核实和补偿的情况。

当前 try/catch 不能被描述为“覆盖了所有远程副作用”：券预占及其部分处理在该块之前，清购物车和支付准备也有自己的失败窗口。讨论系统可靠性时，应沿每一个副作用核查，不从“有补偿”推导出所有失败都被完美覆盖。它是一套可分析的实现，不是整体正确性的证明。

**连续追问。** 问：数据库 insert 执行后一定已经提交吗？答：事务内 SQL 执行不等于事务提交。再问：为什么两个商品可能有不同 orderId，却共用 payOrderId？答：子订单便于独立业务处理，支付单聚合一次付款。再问：用户超时重试一定不会再建订单吗？答：库存 operationId 只约束某个库存操作，不自动等于 HTTP 下单请求幂等；需要另外检查是否存在稳定的客户端请求标识与结果查询协议。

## 4. 本地事务、AOP 和 Seata：哪里会一起回滚

`postOrder` 同时出现 `@Transactional` 和 `@GlobalTransactional`。前者一般通过 Spring 代理在方法前后管理本地事务：调用前获取事务资源，成功时提交，满足回滚条件的异常离开方法时回滚。`rollbackFor=Exception.class` 扩展了默认回滚范围；吞掉异常并正常返回可能改变事务结果。

事务边界要按运行配置分析。[启动器](../../../deploy/start-local.ps1) 显式设置 `SEATA_ENABLED=false`，因此本地演示不能声称“Order、Stock、Pay 已由 Seata 全局事务保证原子提交”。Order 本地回滚不会自动撤销 Stock 已经提交的事务，也不能撤销已经成功发送的外部 HTTP 请求。

Feign 不会把 Order 的 JDBC Connection 传给 Stock。Stock 接到 HTTP 请求后有自己的 Spring 调用链和本地事务。把远程服务画在同一个 Java try/catch 内，也不会使其成为一个数据库事务。当前实现因此需要操作幂等、反向补偿以及持久化恢复记录。

还要注意自调用：通过 `this.otherMethod()` 进入同类方法，通常不会重新经过 Spring 代理。不能因为 private/helper 方法附近有注解就推导出新事务。本项目 `cancelUnpaidOrderForPayTimeout` 本身有事务，内部调用 cancelOrder 时可继续参与既有外层事务；具体是否新建事务要看入口和传播方式。相反，Common 的 Inbox 使用 TransactionTemplate 显式创建事务，不依赖同类调用触发注解。

替代方案是恢复单库事务，或者在满足基础设施与契约要求后启用 Seata；也可设计显式状态驱动的补偿流程。方案选择需根据业务允许的中间状态、运维能力和恢复成本，不能只因为分布式事务名词更复杂就认为更好。

**连续追问。** 问：注解还在，为什么不能说全局事务生效？答：注解不是运行证据，启动器关闭了对应能力。再问：REQUIRES_NEW 有什么用？答：独立提交恢复记录，使主业务回滚后仍能追踪失败，但需要保证新事务真的通过代理或模板启动。再问：补偿是否等价于数据库 rollback？答：补偿是新的业务操作，可能失败、延迟或需要人工介入，不能抹掉外部世界已发生的事实。

## 5. 库存查询、行锁和条件更新：最后一件商品如何保护

关键源码是 [SkuStockService.lockAndVerify/changeStockBatch](../../../Simlect-backend/Simlect-stock/app/src/main/java/com/simlect/biz/SkuStockService.java) 和 [库存 SQL](../../../Simlect-backend/Simlect-stock/app/src/main/resources/com/simlect/mappers/SkuStockMapper.xml)。

`getStock` 是读取事实，不能预留库存。`lockAndVerify` 将相同 SKU 的需要量合并，用有序 Map 按稳定顺序获取行锁并核对库存。排序能减少两个请求以相反顺序锁不同 SKU 的风险，但不能宣称消灭所有死锁。

一个容易背错的地方：本地关闭 Seata 时，`lockAndVerify` 的锁属于库存服务这一次 RPC 的事务，返回并完成事务后就释放。它不是一直替 Order 持有到整笔下单提交。最终扣减仍需要自己的并发保护：

```sql
UPDATE sku_stock
SET stock = stock + #{changeAmount}
WHERE product_id = #{productId}
  AND property_value_id_hash = #{propertyValueIdHash}
  AND stock + #{changeAmount} >= 0
```

第一行确定要更新的表；第二行用数据库当前值加变更量，扣两件即 changeAmount=-2；第三、四行共同定位具体 SKU；最后一行拒绝变成负数。Service 检查 affected rows，更新不到行就抛异常。它比“Java 先读 1，再写 0”更能把检查和更新结合起来。

推演两个用户同时买最后一件：A、B 的普通查询都可能看到 1；预检查甚至也可能先后通过。A 的扣减 SQL 成功后，B 的实际更新需根据数据库锁和当前值重新判定，不能再扣成 -1。B 失败后，其订单事务和其他已完成的远程副作用分别按本地回滚与补偿处理。

替代方案是库存版本号乐观锁、独立库存预留记录或单服务串行化，每种都还要处理重试和释放。当前表使用条件更新并有 SKU 键，应结合真实 SQL 和隔离级别分析，不能只喊“加了锁所以不会超卖”。

**连续追问。** 问：为什么已经查库存还可能失败？答：查询与扣减之间其他请求会改变库存。再问：锁两次是否多余？答：预检查改善批量校验体验，最终更新负责保护真实变更；两者事务边界不同。再问：批量扣三个 SKU，第三个不足怎么办？答：库存批量方法的本地事务应使本批次前两个更新也回滚；跨服务其他动作仍需单独处理。

## 6. 库存幂等与响应丢失：为什么补偿也要有前置条件

幂等是同一个业务操作重复执行不会重复产生业务效果。它不等于“任何两个相同参数的请求只允许一次”：两个合法订单可以购买相同 SKU，必须有不同操作标识。

Order 为扣减生成 `order-create-deduct:<payOrderId>`，下单失败反向操作使用 `order-create-compensate:<payOrderId>`。[StockFeignSupport.changeStockBatchIdempotent](../../../Simlect-backend/Simlect-stock/api/src/main/java/com/simlect/api/support/StockFeignSupport.java) 将 operationId、requiredOperationId 和变更项一起发送。Stock 的 stock_change_operation 用唯一 operation_id 记录操作，初始 affected_rows=NULL，完成后写结果，重复调用读取原结果；操作记录与库存变更处于同一库存事务。

为什么不能每次重试都生成 UUID？因为那会被理解为新的库存变更。为什么不是只用 Redis 记一个“做过了”？因为标志写入和数据库库存变更可能分离；持久记录与业务更新同事务更容易验证。不过也不能由此声称所有远程链路都 exactly-once。

**响应丢失推演。** 初始库存 10，买 2。库存服务成功扣成 8，但响应没到 Order；Order 认为调用失败，不能据此断言库存仍是 10。反向补偿带原扣减 ID：如果能确认原操作成功，就回补到 10；同一补偿重复到达，应返回已完成结果，不加到 12。

更难的是补偿先到：原扣减请求还在网络中，库存服务尚未执行。此时直接加 2 会凭空变成 12；只返回“不存在”而不留下痕迹，迟到扣减又会把已取消交易的库存变成 8。当前代码使用取消墓碑：

```java
if (skuStockMapper.insertCancelledChangeOperation(requiredOperationId) == 1) {
    return 0;
}
```

`requiredOperationId` 是原扣减操作，不是本次补偿 ID；方法成功写入该键的取消记录，affected_rows=-1，返回 0 表示无需回补。迟到扣减再尝试创建同一操作键会发现已有终态，从而不扣。若并发原操作已抢到唯一键，代码重新加锁检查它的结果后再决定。墓碑不能随便按短 TTL 清除，否则迟到请求可能重新执行。

**连续追问。** 问：重复和乱序有什么不同？答：重复要识别同一操作；乱序还要识别前置动作是否发生。再问：补偿操作为什么关联原扣减？答：先有真实扣减才应反向加库存。再问：请求超时是否可以直接重试写操作？答：必须复用业务幂等键并具备结果核实机制，超时表示结果未知，不表示失败已确定。

## 7. 支付、状态机与超时消息：让两个竞争者不能都改成功

支付单与订单是不同对象。[LocalDemoPaymentService.confirm](../../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/LocalDemoPaymentService.java) 先检查模拟开关、非生产环境、本人订单、未关闭、未超时及金额，再构造本地通知调用 `orders.paySuccess`，最后回读状态。它不是直接把订单随便 update 成已支付，也没有发生真实扣款。

[OrderStateMachine](../../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/state/OrderStateMachine.java) 把事件和合法迁移集中起来。例如 WAIT_PAYMENT 遇 PAY_SUCCESS 转 PAID，遇 PAYMENT_TIMEOUT 转 CLOSED。更新会携带预期旧状态，避免“前面读到未支付，后面无条件覆盖已经支付”的问题。

`paySuccess` 和 `cancelOrder` 使用 [RedisComponent.runWithPayOrderLifecycleLock](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/component/RedisComponent.java) 的同一支付生命周期锁，按 payOrderId 串行处理相关竞争。Redisson 锁是协调手段，状态条件更新仍是数据库中的一道约束；不要忽略锁释放、事务提交和远程副作用的具体先后关系，也不要把它当作完整的线性一致性证明。

可以具体跟一次调用：公开方法的事务代理先进入，方法体再拿 Redis 锁；`runWithPayOrderLifecycleLock` 的 finally 在回调结束后释放锁，而外层事务通常在公开方法返回后才由代理提交。因此不能假设 Redis 锁一直覆盖到数据库提交完成。另一个请求可能先拿到锁，再遇到尚未提交的数据；条件更新和数据库锁仍然重要。若需要更强的串行边界，可评估把生命周期锁放在事务外层，或在事务完成回调释放，但不要把这种可选改造说成当前已经实现。

下单后的超时事件由 [RabbitMQConfig.payTimeoutDelayQueue](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/constants/RabbitMQConfig.java) 的 TTL 与死信路由送到超时消费队列。处理入口是 [RabbitMQPayOrderDeadListenerComponent.handlePayTimeoutOrder](../../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/component/RabbitMQPayOrderDeadListenerComponent.java)，随后调用 `cancelUnpaidOrderForPayTimeout`。它先看订单是否仍待支付，再看同支付单是否已有已付款子单，才尝试关单。

因此消息的含义是“到时间检查”，不是“无条件关单”。锁忙会返回未处理并触发重试；已有支付结果时跳过关单。TTL、调度和处理都会带来时间误差，不能承诺毫秒级准时。替代方案是定时扫未支付订单；简单但要考虑扫表成本、索引和多实例重复执行。

**连续追问。** 问：用户付款时超时消息正好到怎么办？答：同生命周期锁协调，状态机和当前状态重查限制合法结果。再问：支付晚于订单关闭怎么办？答：现有支付方法有迟到支付退款/补偿分支，不能直接复活已关闭订单；真实资金还需渠道查询和对账。再问：模拟支付通过能证明支付宝接入成功吗？答：不能，它验证的是本地业务路径和状态处理。

## 8. Outbox：数据库提交成功、消息没发出去怎么办

在事务中直接发 MQ，有两个典型窗口：消息发出但订单回滚，消费者看见不存在的订单；订单提交成功但进程在发送前崩溃，超时任务消失。只注册一个内存 afterCommit 回调能改善第一种情况，不能独自补上第二种。

[TransactionalMqSender.sendAfterCommit](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/constants/TransactionalMqSender.java) 的业务消息重载在有事务同步时先 `savePending` 写本地 Outbox，再注册提交后派发。消息意图与订单数据因此可以在同一数据库事务里提交；提交后尝试立即发送，失败由持久记录支持后续恢复。另一个仅接收 Runnable 的重载没有相同的持久消息保证，面试时不能混为一谈。

[OutboxMessageServiceImpl.dispatch](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/service/impl/OutboxMessageServiceImpl.java) 先用条件 UPDATE 领取数据库租约，每次领取生成新 owner，再调用 `ReliableMessageSender.sendOutboxWithConfirm`。与消息业务键不同，owner 标识本次尝试，防止旧请求的晚结果覆盖新领取者。

[LocalMessageOutboxMapper.xml](../../../Simlect-backend/Simlect-common/src/main/resources/com/simlect/mappers/LocalMessageOutboxMapper.xml) 的完成更新会检查当前 owner 及租约未到期。发送时不长期持有一笔数据库事务等待网络；租约过期可以被回收。[OutboxDispatchTask.dispatch](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/task/OutboxDispatchTask.java) 扫待发送、失败或过期发送中记录，受调度开关、批次和最大尝试次数控制。不能只看存在 @Scheduled 就断言当前环境一定在补投。

发送确认又是什么？[MqPublisherConfirmHelper.sendAndAwaitConfirm](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/component/MqPublisherConfirmHelper.java) 等待 Broker confirm，并处理不可路由 return；这证明不了消费者业务成功。若 Broker 已接收而 SENT 写库失败，记录仍可能被再次派发，所以消费端仍需要幂等。

**连续追问。** 问：Outbox 为什么放业务库？答：让业务状态和发送意图尽可能共享本地事务。再问：数据库租约能保证消息只投一次吗？答：不能，响应丢失仍会重投；它解决有效领取和晚结果覆盖问题。再问：无限重试是否更可靠？答：不是，持续故障需退避、上限、可观察的终态及人工处理，避免无休止占用资源。

## 9. Inbox、ACK 与远程补偿：每个“成功”究竟代表什么

Outbox 关注生产者，Inbox 关注消费者。入口 [MqListenerHelper.consumeInboxBatch](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/component/MqListenerHelper.java) 调 [MqConsumerIdempotencyHelper.executeBatch](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/component/MqConsumerIdempotencyHelper.java)。后者通过 TransactionTemplate 建立本地事务，以 consumer 与 eventId 定位 Inbox 记录，锁住记录、执行业务、标记 DONE，事务提交返回后才允许 ACK。

consumer 用来区分不同订阅者；eventId 表示稳定事件身份。同一事件可能被不同业务消费者各执行一次，不能只用一个全局“这个消息处理过”标志屏蔽其他消费者。字段和 SQL 见 [MqInboxMapper.xml](../../../Simlect-backend/Simlect-common/src/main/resources/com/simlect/mappers/MqInboxMapper.xml)。

推演 ACK 丢失：本地业务和 DONE 已提交，Broker 没收到 ACK，再次投递；消费者查到 DONE，就不重做本地业务，重新确认。反过来，先 ACK 再写库，进程崩溃后 Broker 不再投递，就可能漏业务。失败后先回滚，再在独立事务记录失败次数；超过上限或无效事件会持久化失败信息，并通过 Outbox 备份到失败队列。

这保证的重点是受同一本地事务覆盖的业务效果，不自动覆盖对 Stock 的远程调用。远程已经成功，本地回滚后重投仍可能再次调用，因此 Stock 的 operationId 继续承担去重职责。Inbox 与远程幂等应配合使用，不能互相替代。

远程反向操作也可能失败。[RemoteCompensateRecorder.recordStockChangeBatch](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/component/RemoteCompensateRecorder.java) 保存操作键、前置操作键和变更项；[MqCompensationLogServiceImpl.saveFromFailure](../../../Simlect-backend/Simlect-common/src/main/java/com/simlect/service/impl/MqCompensationLogServiceImpl.java) 使用 REQUIRES_NEW，使订单回滚后仍保留恢复记录。自动重放最终仍通过原接口并复用 operationId，不重新生成业务键。补偿落库自身失败也会记录错误日志，不能宣称数据库或所有依赖同时故障时仍绝不丢信息。

**连续追问。** 问：收到消息为什么还要 Inbox？答：Broker 可能重投，业务要知道本地效果是否提交。再问：有 DONE 就能跳过所有远端核实吗？答：DONE 只证明该事务设计所覆盖的完成结果；外部资金状态另有事实源。再问：补偿队列恢复了是不是就完成对账？答：不是，重试结果未知、超限或落库失败仍需人工查询和对账证据。

## 10. 分页和分类缓存：普通查询也需要正确性设计

[ProductController.buildProductOrderBy](../../../Simlect-backend/Simlect-product/app/src/main/java/com/simlect/controller/ProductController.java) 当前为默认、价格、销量排序都追加 product_id，推荐列表也有唯一兜底。原因是多个商品可能同价、同销量、同创建时间，offset 分页若没有确定总顺序，不同页就可能重复或遗漏。前端去重只能隐藏重复，不能补回没有拿到的商品。

新参数 includeRecommended=true 明确表达首页需要包含推荐商品；旧省略分类的默认行为和空分类兼容仍保留。不要为了填满首页复制商品。唯一排序只保证固定数据集、既定排序下的确定性，并不提供跨多次请求的数据库快照；高频新增或改价时可考虑游标分页，代价是客户端和排序游标协议更复杂。

[SysCategoryServiceImpl.findListByParam](../../../Simlect-backend/Simlect-product/app/src/main/java/com/simlect/biz/impl/SysCategoryServiceImpl.java) 的新逻辑先判断查询形态：无筛选、无分页、无自定义排序才读全量缓存，命中不先查分类表；否则按原查询走 Mapper，不能拿全量缓存覆盖过滤结果。parent 和 propertyQuery 只控制需要的树形及属性。公开分类接口显式要求两者，以保持原有响应形态。

树形转换使用副本，避免一次请求给节点加 children 后污染其他请求；属性先查一批，按 categoryId 分组，再递归挂载，不在每一层重新查全表。写分类成功后刷新快照，空缓存回源重建。但代码刷新缓存仍不是跨 MySQL/Redis 的原子事务；并发写入、数据库写成功而 Redis 失败等问题，需要结合事务时机和失效策略继续评估。

替代方案包括每种筛选独立缓存、短 TTL、提交后失效或不缓存。当前小分类目录采用全量快照容易理解，前提是明确缓存覆盖范围及失效路径。不能把“有 Redis”写成未经测量的性能提升。

**连续追问。** 问：为什么排序最后还加 ID？答：把排序值相同的行进一步排出稳定顺序。再问：缓存命中为何还可能访问数据库？答：分类缓存只覆盖分类快照，明确要求属性时仍可能做一次属性批量查询。再问：写后刷新就完全一致了吗？答：不是，两个存储之间仍有失败和并发窗口，必须如实描述保证范围。

## 11. 读代码练习：把箭头换成证据

不要先看答案。每题写出文件、方法及一句失败语义，再打开源码检查。

1. 在 OrderController 找出 userId 的来源，解释为什么 PostOrderDTO 不需要相信客户端身份。
2. 在 postOrder 圈出第一次写订单相关 SQL、实际扣库存 RPC 和超时消息登记；判断 SQL 执行与事务提交是否同一时刻。
3. 找出 lockAndVerify 与 changeStockBatch 的事务边界，画出 A、B 同买最后一件时可能交错的顺序。
4. 从 StockFeignSupport 跟到 DTO，找到 requiredOperationId 如何到达 Stock；解释原扣减不存在时为何不能直接加库存。
5. 找出 Outbox 的 savePending、afterCommit、claimLease、completeLease；指出事务回滚后哪些动作不应发生。
6. 在 MqListenerHelper 标出 basicAck 的位置，解释 Inbox DONE 已提交但 ACK 失败后再次投递的结果。
7. 阅读 ProductPaginationMySqlTest，说明它为什么构造相同价格、销量和时间，为什么最终回滚；这种测试不证明什么。
8. 阅读 SysCategoryServiceImplTest，指出哪个断言证明命中不查分类表，哪个证明筛选请求不能被全量缓存替代。

相关入口：[订单实现](../../../Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/impl/OrderInfoServiceImpl.java)、[库存测试](../../../Simlect-backend/Simlect-stock/app/src/test/java/com/simlect/biz/SkuStockServiceTest.java)、[分页数据库夹具](../../../Simlect-backend/Simlect-product/app/src/test/java/com/simlect/controller/ProductPaginationMySqlTest.java)、[分类缓存测试](../../../Simlect-backend/Simlect-product/app/src/test/java/com/simlect/biz/impl/SysCategoryServiceImplTest.java)。测试文件存在不等于本章重新执行过；执行记录应另看验收报告。

## 12. 自测答案与评分标准

每题 5 分，总分 40；同义表达可得分，堆名词而没有因果关系最多 1 分。

1. 登录上下文来自 getTokenUserInfo，进入 Service 的是可信登录身份；仍需检查地址与订单归属。说清这两层得 5 分。
2. 注意优惠券关联也属于订单相关写入，且在库存 try/catch 前；订单和明细 SQL 后才远程扣库存，返回后由代理提交。能区分执行、返回与提交得 5 分。
3. 本地 Seata 关闭，两次库存 RPC 不能被想象成同一长事务。最终库存 SQL 的条件与 affected rows 决定是否真正扣成；画出双方都读到 1、仅一方成功的交错得 5 分。
4. Support 将前置键放入批量 DTO。补偿先到应留下取消墓碑而不加库存，迟到扣减不能再执行；原扣减成功才执行幂等逆向变更。能解释两个相反错误结果得 5 分。
5. Outbox 意图与业务同事务，提交后即时派发，租约扫描负责恢复；回滚不能留下有效业务事件发送意图。能另外区分 Runnable 重载得满分。
6. executeBatch 提交后才 ACK；ACK 丢失会重投，DONE 阻止重复本地业务。补充远程副作用仍需幂等得 5 分。
7. 等值字段能暴露缺少唯一兜底的排序风险；真实 Mapper 夹具检查固定集合分页，回滚保护现有数据。它不能证明并发改价下的快照、系统吞吐或所有业务一致性。
8. verifyNoInteractions 分类 Mapper 或零次 selectList 证明命中路径；筛选字段、分页和排序测试要求访问原查询且不读取 Redis。属性查询与分类查询分开计数。

32 分以上：可以进入连续故障追问；24～31 分：能讲主线，但需补提交顺序和未知结果处理；不足 24 分：先沿一次下单重新标记每个远程调用和本地事务，不急着背分布式事务术语。

最后用两分钟脱稿复述：“客户端提交的是意图，业务事实由 Java 重新核实；本地事务不包办远端提交；库存条件更新和操作记录处理并发与重复；响应丢失要保留未知结果；Outbox 管发送意图，Inbox 管本地消费完成，远程补偿负责后续修复。所有保证都有运行配置与事务范围，模拟付款和源码存在都不能被夸大成生产验收。”
