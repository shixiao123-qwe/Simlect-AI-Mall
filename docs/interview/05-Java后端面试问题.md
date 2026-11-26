# Java后端面试问题：30题

所有源码路径相对 `E:/Simlect-AI-Mall-main/Simlect-AI-Mall-main/Simlect-backend`。先读02的执行链再练习，不需要逐字背答案。回答时先讲业务问题，再讲代码机制、失败边界和取舍。

## 1. 为什么拆成这些微服务？

参考回答：商品拥有SKU价格，库存拥有可售数量，订单组织交易，支付封装渠道，职责按业务域分离。`Simlect-product/app/src/main/java/com/simlect/biz/ProductInternalService.java`和`Simlect-stock/app/src/main/java/com/simlect/biz/SkuStockService.java`通过API契约协作。拆分带来网络失败和一致性成本，不能只说“高并发必须微服务”。

追问：实习项目有必要拆这么多吗？答：作为已有架构做二开，优先遵守边界；小团队新项目也可以模块化单体起步，我没有继续增加新微服务。

## 2. Gateway具体做什么？

参考回答：`Simlect-gateway/src/main/resources/application.yml`配置按路径转发和改写；AuthGlobalFilter校验用户会话，InternalTokenGlobalFilter处理内部令牌，OriginCheckGlobalFilter处理来源。业务状态校验仍在领域服务，网关不是订单规则中心。

追问：Feign都经过Gateway吗？答：不是。StockFeignClient等按服务名经Nacos发现直达服务；Python内部HTTP入口使用Gateway。要看调用配置而非照抄注释。

## 3. 一域一库怎样关联数据？

参考回答：`Simlect-product/app/src/main/java/com/simlect/biz/ProductInternalService.java#snapshotBatch`输出商品、SKU、属性快照；订单和购物车通过ProductFeignSupport组合使用，不跨库Mapper JOIN。需要当下结果用RPC，异步同步走MQ。

追问：直接JOIN更快为什么不用？答：跨库耦合权限、发布与可用性；可以为读侧建设专用视图，但写侧仍保持领域权威。

## 4. 用户身份怎么传，为什么不能相信userId参数？

参考回答：`Simlect-cart/app/src/main/java/com/simlect/controller/ProductCartController.java#add2Cart`从TokenUserInfoDTO取userId并覆盖请求。deleteCart在查询条件同时绑定cartId和userId。登录是身份认证，记录归属还要做授权检查。

追问：过滤器通过就够了吗？答：不够，别人记录ID仍可能被猜到；Service/SQL必须绑定当前用户或核对归属。

## 5. 内部接口怎样保护？

参考回答：`Simlect-common/src/main/java/com/simlect/web/InternalApiAuthFilter.java`保护服务的/internal路径；FeignInternalAuthInterceptor传X-Internal-Token，Gateway也做校验。令牌为空或不匹配应拒绝，不因为“是内网”而放行。

追问：共享内部令牌有什么局限？答：它是服务间身份，权限粒度较粗；不能替代用户token，生产还需网络隔离和密钥管理。

## 6. 如何区分接口参数错误和业务错误？

参考回答：AiProductFilterDTO用Bean Validation限制候选数、金额精度和属性长度；AiProductFilterService验证区间、状态和库存事实。错误通过AGlobalExceptionHandlerController和ResponseVO统一表达，不应把下游异常堆栈直接返给浏览器。

追问：@Valid为什么还要Service验证？答：直接调用Service可能绕过Controller，跨字段不变量也需要业务层保证。

## 7. 金额为什么用BigDecimal？

参考回答：`AiProductFilterService.filter`比较sku.price使用compareTo，不用二进制浮点作预算边界；SQL价格是decimal(10,2)。商品最低价只是召回参考，具体规格才是权威价格。

追问：equals能比较金额吗？答：equals对scale敏感，如1.0和1.00；数值大小一般用compareTo，渠道金额按既有工具统一精度。

## 8. 你怎样减少N+1？

参考回答：新增Stock readBatch以最多50个商品ID一次查SKU库存；Product复用snapshotBatch三类批量读取，按productId分组，再一次Feign取库存。它避免逐SKU网络请求，但不代表整个项目已无N+1，购物车旧填充仍逐SKU查库存。

追问：批量是不是越大越好？答：不是，限制商品数、SKU数和响应大小，防止SQL包和内存膨胀。

## 9. 库存表索引怎么支持查询？

参考回答：`sql/03_stock.sql`主键是(product_id,property_value_id_hash)。单SKU读取匹配全主键，新增按product_id IN可使用联合主键左前缀。是否实际走索引还应在代表性数据量上看EXPLAIN，不能只凭DDL断言性能。

追问：stock小于阈值为什么可能慢？答：stock不是该联合主键前缀；是否加索引要看低库存查询频率和写入成本，此次未盲目加索引。

## 10. 如何防止超卖？

参考回答：`SkuStockMapper.xml#changeStock`用条件UPDATE限制stock + changeAmount >= 0，更新失败转业务错误；Service事务和FOR UPDATE参与并发协调。前端“库存10”只是快照，不能代替扣减时原子判断。

追问：先select库存再update够吗？答：不够，两个请求可同时看到足够库存；必须靠数据库条件更新/锁保证最终修改。

## 11. FOR UPDATE锁到什么时候？

参考回答：`SkuStockMapper.selectByKeyForUpdate`的行锁属于数据库事务。事务提交/回滚释放；本地关闭Seata时，不能假设一次Feign校验的锁会跨到下一次独立RPC。

追问：怎么解释lockAndVerify这个方法名？答：必须结合Stock本地事务和后续条件扣减，名字本身不构成分布式锁保证。

## 12. 库存幂等键为什么要稳定？

参考回答：SkuStockService和stock_change_operation使用operation_id记录结果；订单创建用`order-create-deduct:`加支付单号。网络超时后使用同一ID重试，服务可识别已执行结果。

追问：每次UUID不行吗？答：每次新ID就变成新的业务操作，会重复扣减或回补。

## 13. @Transactional能回滚远程Feign吗？

参考回答：本地事务只覆盖本地数据库；`OrderInfoServiceImpl.postOrder`跨Product/Stock/Pay等服务，还需要分布式事务配置或幂等补偿。源码存在@GlobalTransactional，但当前演示SEATA_ENABLED=false，不能说本地事务能回滚所有远程副作用。

追问：那失败怎么收尾？答：跟踪库存变更是否尝试/完成，以稳定补偿ID回补，失败记录补偿任务，不能仅依赖catch打印。

## 14. Spring自调用有哪些事务陷阱？

参考回答：事务一般由代理拦截，直接this调用不会重新经过代理。订单的外层public事务方法与内部private辅助方法需要一起看，不能把private方法上的注解想成独立事务。此处不凭自调用存在就认定Bug，要确认外层边界和实际调用者。

追问：怎么验证？答：写包含真实代理的集成测试，观察提交/回滚边界；普通Mockito测试无法证明代理事务语义。

## 15. 下单金额从哪里来？

参考回答：OrderInfoServiceImpl.postOrder用ProductFeignSupport.snapshotBatch获取SKU，按购买数量和券规则计算金额，再生成支付信息。用户提交的是购买意图，不能信任前端price字段。

追问：推荐显示199，下单涨价怎么办？答：结算重新核价并让页面明确展示，不能承诺推荐价格永久有效。

## 16. 为什么购物车有price和addPrice？

参考回答：ProductCartServiceImpl.findListByPageAndUserId中price填最新SKU价格，addPrice保留加入时价格参考。两者用于表达价格变化，交易仍按权威现价和订单规则核算。

追问：购物车查出来后立即下单还有竞态吗？答：有，必须在下单重新查询和校验，展示快照不构成锁定。

## 17. DB提交和MQ发送怎样避免双写缺口？

参考回答：TransactionalMqSender.sendAfterCommit有事务时先savePending到Outbox，提交后tryDispatch。进程在提交后宕机，持久化记录仍可由OutboxDispatchTask补投；无事务路径走ReliableMessageSender。

追问：只用afterCommit够吗？答：不够，提交后进程退出可能没发出去；Outbox记录才提供可恢复证据。

## 18. Outbox是不是保证Exactly Once？

参考回答：不是，Outbox保证有机会重投，可能重复。消费侧仍要业务幂等和去重；OutboxDispatchTask的配置条件、重试上限也会影响运行效果。

追问：怎么处理重试耗尽？答：需要可观测失败状态和人工/补偿流程，不能无限重投或静默丢弃。

## 19. 超时订单怎么关闭？

参考回答：RabbitMQ TTL+死信进入RabbitMQPayOrderDeadListenerComponent.handlePayTimeoutOrder，再调用cancelUnpaidOrderForPayTimeout。方法重新查状态，已支付或同支付单已有进展则跳过，锁忙返回待重试。

追问：消息晚到怎么办？答：以当前数据库状态为准，不因“收到了超时消息”就强制关闭已支付订单。

## 20. 关单怎么避免重复加库存？

参考回答：Order关闭分支调用StockFeignSupport.changeStockBatchIdempotent，恢复操作使用稳定ID，库存域记录执行。MQ消费去重之外，最终业务变更也要幂等。

追问：只用Redis消费去重够吗？答：Redis标记过期、失败重试、不同入口都可能再次触发，库存持久化operationId更接近业务事实。

## 21. 支付回调怎么防伪和防重？

参考回答：PayNotifyController先调用PayChannel.payNotify验签解析，再按支付单号取Redisson锁，转OrderFeignSupport.paySuccess。Order状态机处理重复完成。签名、互斥和业务幂等分别解决不同问题。

追问：锁没抢到为什么不能直接改状态？答：另一请求可能正在处理；当前代码返回渠道成功，应结合生命周期和重试策略评估可观测性，不能并发重复执行。

## 22. Redisson看门狗解决什么，不解决什么？

参考回答：PayNotifyController用tryLock等待并使用看门狗续期，避免固定租期短于业务时长导致同时进入。它不替代金额校验、所有权校验和持久化幂等。

追问：业务线程卡死怎么办？答：锁也可能长期续期，需要调用超时与故障恢复，不能把锁当成无限等待的理由。

## 23. 退款远程成功、本地失败怎么办？

参考回答：Order退款保持稳定退款单号；PayChannel4AliPay.refund把它传为out_request_no并用Redis请求闸防重。重试用同一个渠道幂等号，避免本地回滚后再次退款。

追问：为什么不能异常后随机新建退款单号？答：渠道会视为另一笔请求，可能二次退钱。

## 24. Redis失效会不会产生旧价格？

参考回答：Redis在本项目用于会话、验证码、幂等和部分业务缓存；新增导购最终读Java SKU/库存。它仍是瞬时快照，库存可能在推荐后变化，交易时必须重新验证。

追问：缓存TTL能保证强一致吗？答：不能，TTL只是限制陈旧时长，严格结算必须访问权威业务路径。

## 25. 布隆过滤器有假阳性还是假阴性？

参考回答：正确维护的布隆过滤器有假阳性，即说可能存在但实际不存在；数据已插入过滤器后不应无故假阴性。本地直接SQL导入绕过新增维护，应用视角就会拒绝合法ID，这不是数学性质失效而是维护流程缺失。

追问：怎么修？答：通过应用新增或重新预热。案例已实际通过重启Product加载6个商品ID解决。

## 26. ES为什么不是交易数据库？

参考回答：EsSearchComponent用索引召回，异步更新可能落后。AiProductFilterService从MySQL读取真实SKU，再通过Stock核验；订单不依赖ES库存。

追问：搜索索引同步成功为何曾查不到？答：原contains用于IK分词text字段，长词没有完整词项；改分词matchesAll或keyword子串并做真实接口验证。

## 27. Feign fallback应该返回什么？

参考回答：StockFeignFallbackFactory返回库存服务不可用，StockFeignSupport通过统一拆包抛业务异常。不能返回0扣减成功或伪造充足库存，以免故障时继续卖货。

追问：推荐可以降级吗？答：可以返回暂不可用/不保证约束的普通搜索，但必须标明；交易扣减必须失败关闭。

## 28. 怎样设计测试而不只测getter？

参考回答：AiProductFilterServiceTest覆盖预算边界、下架、缺库存、硬属性缺失和库存服务失败；AiFilterControllerTest验证Bean Validation不让非法请求进入Service。Mockito控制故障点，真实链路另做本机MCP和演示数据验收。

追问：单测通过证明事务正确吗？答：不能，数据库锁、隔离级别、代理和MQ网络还需集成/并发测试。

## 29. 16GB本地怎么启动这么多服务？

参考回答：deploy/start-local.ps1使用低内存profile、SerialGC、限制CPU线程、逐服务探活，Node限制堆与Rust线程；用进程记录防止重复启动。堆上限不等于进程总内存，Windows分页和IDE也占预算。

追问：为什么不把堆压到几十MB？答：过小会频繁GC或启动失败，应该按服务实际负载观察，而不是盲目追求低数字。

## 30. 什么成果能写成你自己的？

参考回答：我实现结构化需求、SKU权威筛选、批量库存、受控MCP推荐/比较，修复搜索调用和中文查询，建设评测与源码文档。原有微服务、Outbox、退款幂等和Seata基础不写成我的原创。

追问：有没有量化提升？答：有当前固定测试集的真实结果，但没有同数据集原版本基线，不能写“性能提升50%”或“全系统准确率100%”。
