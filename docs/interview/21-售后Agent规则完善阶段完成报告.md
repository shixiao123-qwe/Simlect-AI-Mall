# 售后 Agent 完善：阶段完成报告

日期：2026-11-04。项目根目录：E:/Simlect-AI-Mall-main/Simlect-AI-Mall-main。

## 1. 本阶段结论与范围

本轮在既有售后流程上增加期限规则计算器、资格核验范围说明、政策缺失提示及回归用例。没有新增微服务，没有调整数据库结构，没有升级依赖，没有修改真实资金配置或密钥。

**七天规则的计算逻辑已实现，14 项规则单元测试通过；真实签收时间接入仍未完成，因此线上资格接口不能宣称已经判断“签收七天内”。** 当前返回的 eligible 仍仅代表能否提交人工审核申请，不代表政策全部满足、审核批准或退款成功。

Git 检查结果：本地目录不是 Git 仓库，无法提供基于 Git 的分支或完整 diff。只修改下列列明文件，保留既有功能。

## 2. 第一阶段：只读审查记录和功能完成度

以下路径以项目根目录为起点。

| 能力 | 真实源码与方法 | 状态与事实 |
| --- | --- | --- |
| LangGraph 售后分支 | Simlect-backend/Simlect-agent/app/graph/nodes.py 调用 after_sale_turn；graph/state.py 的 after_sale_handled | 已有；有界分支，复用最终输出流程，不新增多 Agent 循环 |
| 意图与会话 | app/services/after_sale_turn.py：after_sale_intent、after_sale_turn；session_memory_service 的 afterSaleContext | 已有；订单、物流、政策、资格、申请、原因补充及进度 |
| 订单详情 | app/services/after_sale_service.py：order_detail；mcp_server/server.py：GET_ORDER_DETAIL | 已有；先查本人订单，再获取明细 |
| 订单与物流工具 | mcp_server/server.py：QUERY_ORDERS、QUERY_LOGISTICS | 已有；不另建同义工具 GET_USER_ORDERS / GET_LOGISTICS_INFO |
| 资格工具 | server.py：CHECK_AFTER_SALE_ELIGIBILITY → services/after_sale_service.py：eligibility/check | 已有，本次补充 Java 期限核实状态展示 |
| 提案 | CREATE_AFTER_SALE_REQUEST → after_sale_service.propose → pending_action_service.create_pending | 已有，仅生成 Redis 确认令牌，不提交申请 |
| 用户确认执行 | app/services/action_execute_service.py：AFTER_SALE_REQUEST 分支 | 已有；带登录 token 请求 /api/order/afterSale/create，不接受模型指定身份或金额 |
| Java 内部资格接口 | Simlect-backend/Simlect-order/app/src/main/java/com/simlect/controller/internal/AfterSaleInternalController.java：check → AfterSaleService.check | 已有内部鉴权；校验 DTO、订单归属与业务状态 |
| Java 申请接口 | controller/AfterSaleController.java：create → biz/AfterSaleService.create → mappers/AfterSaleMapper | 已有；先核对归属，按 order→item 加锁，再处理请求幂等及处理中申请 |
| 审核、验货、退款 | controller/admin/AfterSaleAdminController.java：review/inspect/refund → biz/AfterSaleWorkflowService 同名方法 | 已有；管理员登录、敏感操作确认，退款仍为本地模拟 |
| 用户寄回与进度 | AfterSaleWorkflowService.submitReturn/mine/detailOwned | 已有；本人归属、阶段及类型校验 |
| 政策检索 | app/services/after_sale_policy.py：query_policy；knowledge/after_sale_policy.json | 本地词项匹配原文，非已验证向量 RAG；本次升级 demo-v3 |
| 七天期限 | 新增 biz/AfterSalePolicyRules.evaluate | 部分完成：算法可测；缺可靠签收事实和正式政策，线上保持资料不足 |
| 运费、到账时效 | policy JSON、AfterSaleService 的 UNCONFIGURED 元数据 | 未配置正式业务规则，返回资料不足及人工核实 |
| 换货 | DTO、表约束支持 exchange；AfterSaleWorkflowService.review 拒绝批准换货履约 | 部分完成：申请结构支持，履约未实现；本轮仅设计 |

### 数据表、字段和可靠性

- sql/05_order.sql：order_info.order_time 是下单时间；order_status=3 仅说明完成，不能推断签收时刻。
- order_logistics_info：order_id、user_id、logistics_status、logistics_no 等；**没有 signed_at / received_at**。
- order_logistics_info_record：record_time、record_address 是轨迹记录时间与自由文本，没有可信签收事件类型及来源标记，不能取最后一条冒充签收。
- biz/impl/OrderInfoServiceImpl.confirmOrderReceipt：修改订单状态并增加销量，不记录签收时间，也没有保存可追溯的承运商签收事件。
- sql/22_after_sale.sql：after_sale_request 包含 request_id、user_id、order_id、order_item_id、request_type、reason、status、policy_version；active_item_id 是生成列并有唯一约束，保证同明细至多一条处理中申请。after_sale_log 保存操作记录。
- sql/23_after_sale_fulfillment.sql：after_sale_fulfillment 保存 phase、review_note、return_tracking、inspection_note、refund_amount、refund_reference、simulated 等，不包含换货发货业务模型。

审查后的改造清单：复用全部查询与确认流程；补规则计算器和核验范围元数据；修政策缺失及不完整语料处理；增加期限、越权和重复申请回归；换货只设计，不在本阶段插入新状态。

## 3. 本次 Java 期限规则设计

新增 AfterSalePolicyRules，独立于 LLM。配置 return-window-days 默认 0、return-window-source 默认空；不填写 7 天作为默认商家承诺。

规则需要四类输入：申请类型、可信签收时间与可信标记、商品适用性、服务器当前时刻。商品适用性分 STANDARD / SPECIAL / UNKNOWN，不把未知商品当普通商品。

判定顺序：

1. 未发货退款不使用签收退货期限，返回 NOT_APPLICABLE，原订单状态检查仍然执行。
2. 天数不在 1..365 或来源为空：POLICY_MISSING，人工核实。
3. 签收时间缺失、未核验：RECEIPT_MISSING。
4. 签收时间在未来或当前时间缺失：RECEIPT_INVALID。
5. 特殊商品或适用性未知：SPECIAL_PRODUCT / APPLICABILITY_UNKNOWN。
6. 所需资料完整时按配置计算 WITHIN_WINDOW / OUTSIDE_WINDOW。

边界约定：配置天数 × 24 小时，期限终点包含在内，超过终点 1 纳秒即超期。这是可测试的计算约定，不是对任何法律或商家政策的解释；正式政策若按自然日、次日零点或其他时区计算，需重新实现并测试。超出普通期限也不代表所有质量售后权利失效，返回人工核实。

当前 Order 接口显式传入 Receipt.unavailable 和 Applicability.UNKNOWN，绝不读取订单创建时间作替代。即使误配置天数和来源，缺少签收证据仍不能进入 WITHIN_WINDOW。算法尚未连接到可靠签收事实，不能当成在线七天自动裁决功能。

响应新增 eligibilityScope=APPLICATION_ONLY、returnWindowStatus、returnWindowReason、manualReviewRequired=true、freightStatus=UNCONFIGURED、refundArrivalStatus=UNCONFIGURED。保留原 eligible 与业务状态校验语义，不因资料缺失切断人工审核通道。

## 4. 政策和 Agent 调用流程

政策版本为 demo-v3，source_type=local_demo，明确标记无商家正式依据；configured_terms 中期限、期限来源、运费和到账时间均为 null，换货履约为 false。

query_policy 继续输出原文、文件来源、版本与 AS-004 承诺限制；新增语料结构校验，语料缺失/损坏或缺少限制文档时返回暂不可用；支持“7 天”带空格表达。AS-005 解释为何可提交申请不代表期限已通过。

实际链路：

用户 → LangGraph 售后分支 → MCP CHECK_AFTER_SALE_ELIGIBILITY → Java 内部鉴权及 AfterSaleService.check → 原业务资格 + 新核验范围 → Agent 展示结果。

用户申请 → Java 资格预检 → Redis 提案与确认卡（提示期限待核实）→ 用户点击确认 → action_execute_service → Java 登录身份校验 → AfterSaleService.create 在锁内重查 → 保存申请与日志 → 人工审核、寄回、验货及模拟退款。

政策检索是另外的只读工具，不声称现有资格路径每次都先检索政策；最终业务判断仍然只在 Java 完成。

## 5. 换货设计评估（未实现）

当前 exchange 只受理申请结构，不能批准出库。可靠换货需要库存预留、退回验货、换出 SKU/价差处理、发货及物流回调、超时释放、重试与幂等。当前缺少这些事实与接口，强行复用退款完成状态会造成“已完成但未发货”。本阶段保留拒绝批准的安全边界。

后续建议在现有 Order 内扩展独立换货履约记录，而非新微服务：保留 request_id，增加 replacement_sku_id、replacement_quantity、reservation_id、outbound_order_id、outbound_tracking、signed_at 及幂等版本。建议阶段 WAIT_RETURN → WAIT_INSPECTION → WAIT_REPLACEMENT_STOCK → READY_EXCHANGE → EXCHANGE_SHIPPED → EXCHANGE_COMPLETED；库存不足留在等待或人工协商，不报告完成。

两周可拆为：先补结构、测试及人工履约接口，再做库存预留与重试，最后联调物流、补偿和界面。没有库存/物流契约和真实测试前不宣称换货闭环；现有退款状态约束本次不变。

## 6. 文件修改清单

- Java 新增 biz/AfterSalePolicyRules.java（位于 Simlect-backend/Simlect-order/app/src/main/java/com/simlect/）：独立期限规则、证据与适用性输入、结果状态。
- Java 修改 biz/AfterSaleService.java：保留申请资格；增加范围及资料不足字段，政策版本 demo-v3。
- application.yml：两个默认关闭的期限配置；没有写入正式天数或政策。
- 新增 src/test/java/com/simlect/biz/AfterSalePolicyRulesTest.java：期限边界、来源缺失、异常及特殊商品。
- 修改 AfterSaleServiceTest.java：申请资格范围、资料不足、越权、处理中申请。
- Python services/after_sale_service.py：显示 Java 期限核实信息，提案提示未核实。
- Python services/after_sale_policy.py：语料校验、限制文档检查、带空格天数词项。
- knowledge/after_sale_policy.json：来源范围、未配置条款、AS-005 与版本更新。
- evals/test_after_sale.py：政策缺失/异常语料、Java 限制展示、提案提示回归。
- deploy/test-local-memory-safe.ps1：新增 AfterSale 测试范围，分时执行 Order 相关回归及打包后恢复；pytest 临时目录放项目日志内。
- deploy/start-local.ps1、deploy/repair-local-services.ps1：允许显式传入两项新期限配置，默认仍未配置；不写入密钥或正式条款。
- 本报告及面试笔记附录：标明已完成、部分完成和未实现。

## 7. 测试结果

### 实际执行结果

- 分时入口：powershell -NoProfile -ExecutionPolicy Bypass -File ./deploy/test-local-memory-safe.ps1 -Suite AfterSale。
- Python：257 passed，10.85 秒。包含原有 Agent、购物、售后及公开资料回归，不是 257 条新编用例；本轮新增 12 个执行用例。
- Java：公共模块 12 + Order 模块 272 = 284 项，Failures/Errors/Skipped 均为 0。规则类新增 14 项，资格服务新增 3 项；既有售后流程 25 项、订单服务 112 项及模拟支付、状态机、超时关单、Controller 测试保留运行。未重复运行其他所有微服务的完整测试套件。
- Maven 离线 package 成功，新的 simlect-order-1.0.0.jar 已生成并由启动器加载。
- 前端：主页错误停止/重试、模拟支付、售后履约、导购卡片、后台验证码行为检查全部 exit=0；vue-tsc exit=0。
- 启动器离线回归通过，包括脚本解析、进程归属、内存双门槛与健康超时。
- Order 实际只读接口：code=200、policyVersion=demo-v3、eligibilityScope=APPLICATION_ONLY、returnWindowStatus=POLICY_MISSING、manualReviewRequired=true，运费及到账字段均为 UNCONFIGURED。使用现有订单核对归属，换成非本人 userId 返回 code=600。没有新增申请、审批或资金操作。

证据目录：logs/memory-safe-tests/20261104-110717-420（python.xml、python.log、Simlect-order-app.log、各前端检查日志、restore.log、result.json）；只读响应摘要 logs/after-sale-phase-live-20261104.json；总日志 logs/after-sale-phase-20261104.log。

首轮使用系统临时目录的 Python 执行出现错误并被中止，不计为通过；将 pytest basetemp 放入项目日志目录、在分时入口重跑后得到上述结果。pytest-asyncio 存在已有的 fixture loop scope 弃用提示，本阶段未升级依赖。

### 新增 Java 测试场景（17 项）

1. 七天截止前 1 秒。
2. 恰好七天，包含终点。
3. 超过七天 1 纳秒。
4. 默认未配置政策。
5. 天数有值但政策来源缺失。
6. 期限为负数。
7. 期限超过允许的配置范围。
8. 没有签收时间。
9. 时间存在但未经核验。
10. 签收时间在未来。
11. 特殊商品不自动套用普通期限。
12. 商品适用性未知。
13. 未发货退款不依赖签收期限。
14. 六天仍在配置期限内。
15. 申请可提交但期限状态未知，返回 APPLICATION_ONLY。
16. 新元数据不能绕过订单归属校验。
17. 资料不足也不能绕过已有处理中申请限制。

Python 新增 12 项：五类缺少正式条款的问题、五类异常语料结构、Java 期限限制展示、提案保留未核实说明。已有越权、重复请求、原因补充及确认执行测试保留。

### 验证边界

以上主要是离线单元/契约回归及真实接口只读核对，不等于真实签收事件或正式七天政策验收。本轮没有运行专门 MySQL 事务夹具套件，没有真实快递、支付退款或向量模型调用，没有浏览器全流程人工验收。历史 surefire XML 不当作本轮执行证据，以带本轮时间的 Maven 日志为准。

## 8. 面试学习与表达

为什么改：原接口的 eligible 容易被理解成“已满足全部退货政策”，实际上只校验能否提交申请。新增范围与资料状态，使客户端不会把未知期限解释成通过。

使用的技术：Spring 组件与配置、Java record/enum、Instant/Duration、JUnit 与 Mockito；Python 版本化资料检索、异步 HTTP 工具、Redis 提案及用户确认。没有新增向量服务。

如何防误操作：模型只提案；确认令牌由服务端生成，用户归属和订单状态由 Java 校验，创建时在锁内重查，唯一约束阻止同明细并发申请，退款仍需管理员审核与验货。

面试追问“七天判断实现了吗”：回答“完成了期限规则计算和边界测试，但系统尚无可靠签收时间及正式政策；当前线上返回资料不足并进入人工核实。我没有拿下单时间或模型猜测冒充签收事实。”

追问“RAG 做了吗”：回答“本阶段使用带版本和来源的本地词项检索，返回原文；向量检索关闭，未验证向量 RAG 的效果。”

追问“运费和多久到账”：回答“缺正式依据，返回 UNCONFIGURED 并转人工；模拟退款不等于资金到账。”

## 9. 剩余工作与启用条件

1. 建立可信签收事实：专门签收时间、来源、事件 ID、幂等写入和审计；区分用户确认与承运商签收，不回填伪造历史时间。
2. 商家确认正式期限、适用商品/例外、时间计算口径与政策生效版本；在 Java 接入事实源并同时测试申请与审核重检。
3. 配置运费承担、到账时效前必须有正式来源；退款成功与实际到账分别建模。
4. 按第五节独立实现换货，完成库存及物流验证后才开放审核。
5. 向量 RAG 需另行开启 Embedding、构建索引并做真实检索评测。

## 10. 最终运行状态
分时测试 result.json：Failures 为空，RestoreExit=0。10 个 Java 服务、MCP、Agent 及 web 恢复就绪，地址 http://127.0.0.1:6001/。恢复完成时物理余量 1770MB、提交余量 4071MB，达到当前 1GB/2GB 硬门槛，未达到 4GB 物理目标。这是启动日志的瞬时读数，不是长期负载承诺。
