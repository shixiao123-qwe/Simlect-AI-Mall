# 售后 Agent v1：实现、验收与面试笔记

> 2026-11-02后续更新：售后已新增管理员审核、退货物流/人工验货、受控退款执行及实时进度。原文中“尚未实现审核履约”等是此前阶段记录，最新源码与验收见 [15-售后审核履约设计与验收](15-售后审核履约设计与验收.md)。

日期：2026-11-02。项目根目录：`E:\Simlect-AI-Mall-main\Simlect-AI-Mall-main`。下文路径相对于此根目录。

## 交付范围和原有能力

这次实现的是同一 AI 平台中的售后工作流分支，不是新建一个独立进程或复杂多 Agent 系统。原有订单、物流、确认卡、退款资金流程已存在；新增申请资格、申请持久化与日志、政策证据和安全编排。没有重新开发支付或库存扣减。

已有 `PROPOSE_REFUND` 确认后走原退款执行接口，属于原功能；新 `CREATE_AFTER_SALE_REQUEST` **只创建 Redis 提案**，确认后走独立申请接口，只到待审核。两者业务含义不同，不能把申请结果写成“已退款”。旧页面退款功能保留，不宣称本次已替换系统所有退款入口。

本次不交付审核管理界面、退货运单创建、仓库验货、自动打款或自动换货。APPROVED/REJECTED/COMPLETED 是后续扩展状态，尚无相应审核/履约接口。普通用户不能通过请求修改这些状态。

## 架构图与执行链路

```text
Vue 智能客服（原页面）
  -> Python LangGraph entry -> build_context -> agent_loop
       -> after_sale_turn：规则理解、补参、恢复同一用户的售后上下文
           -> QUERY_ORDERS（复用，对应 GET_USER_ORDERS）
           -> GET_ORDER_DETAIL（新增，先验证本人归属再查明细）
           -> QUERY_LOGISTICS（复用，对应 GET_LOGISTICS_INFO）
           -> QUERY_AFTER_SALE_POLICY -> 本地版本化片段检索
           -> CHECK_AFTER_SALE_ELIGIBILITY -> Gateway -> Order Java
           -> CREATE_AFTER_SALE_REQUEST -> Redis pending（尚未写申请表）
       -> finalize -> ACTION_CONFIRM 卡 -> Vue 展示申请类型/商品项/原因/风险提示
  用户点击确认
  -> 原 confirmAction -> PendingActionService.confirm（归属 + TTL + 锁 + CAS）
  -> ActionExecuteService.execute（AFTER_SALE_REQUEST）
  -> 用户 token -> Gateway /api/order/afterSale/create
  -> AfterSaleController.create -> AfterSaleService.create -> AfterSaleMapper
  -> MySQL after_sale_request + after_sale_log（同一事务）
  -> 返回 PENDING_REVIEW，不调用 Pay 或 Stock
```

售后分支优先于购物分支；处理完成后直接收束，不经过普通商品卡补全。因此上一轮“预算300”不会把“退款政策”误导到推荐商品。此分支每轮一次路由和有限工具调用，不增加自由循环。原 LangGraph ReAct 上限继续保留。

## 新接口与数据权威

| 接口 | 身份与作用 |
|---|---|
| POST `/internal/order/afterSale/eligibility` | 内部令牌；body userId/orderItemId/type；Java 校验订单归属，返回 eligible/reason/policyVersion |
| POST `/api/order/afterSale/create`（Gateway） | 登录 token；body requestId/orderItemId/type/reason；用户身份从服务器登录上下文取得 |
| POST `/api/order/afterSale/mine`（Gateway） | 登录 token；返回本人最近30条申请，尚未接入独立售后列表 UI |

Java 控制器实际映射省略 Gateway `/api` 前缀。用户接口使用已有 `@GlobalInterceptor(checkLogin=true)`；内部只读接口使用已有 InternalApiAuthFilter。测试中的 standalone MockMvc 只验证参数与身份传递，不等于对生产过滤器做了完整渗透测试。

金额不属于申请 DTO，模型不能传退款金额。资格检查不是最终退款金额计算，也不是付款渠道批准。金额、权限和状态的真正业务执行仍由原 Java 流程负责。

### 演示资格规则

- order_item_status 必须为1（正常）。已退款、不存在、他人的订单项不允许申请。
- refund：订单已付款待发货1或部分退款7，可以提交人工审核。
- return_refund/exchange：已发货2、已完成3或部分退款7，可以提交人工审核。
- 已有 PENDING_REVIEW/APPROVED 申请时，不再创建另一条处理中申请。
- 没有签收时间、正式退货期限、商品例外和仓库证据，因此**只判断能否提交审核**，不能据此判定七天无理由、承诺运费或到账时间。

规则位于 `Simlect-backend/Simlect-order/app/src/main/java/com/simlect/biz/AfterSaleService.java#decision`。政策版本 demo-v1 是演示规则版本，不是正式商家法律条款。

## Java Controller → Service → Mapper

1. `AfterSaleController#create` 接收 `AfterSaleDTO.Create`。Bean Validation 拒绝空原因、无效类型、超长值和非法请求编号。
2. `getTokenUserInfo().getUserId()` 取得真实身份；用户不能通过 JSON 中 userId 改归属。
3. `AfterSaleService#create` 读取明细与订单并检查归属；锁订单再锁明细，重查最新订单状态和明细状态。
4. 请求编号已存在时检查同一用户、明细、类型及原因，匹配才返回原记录；编号与内容冲突则拒绝。
5. 再次检查资格；把申请与 INIT→PENDING_REVIEW 日志写入同一 `@Transactional(rollbackFor=Exception.class)` 事务。日志失败时申请也应回滚。
6. 数据库主键保证 requestId 唯一；生成列 active_item_id 在处理中时取 order_item_id，否则为 NULL，唯一索引约束同一明细不能有两个处理中申请。后续审核功能必须同样遵守 CAS 与审计规则。

MyBatis 新 Mapper 使用参数绑定，锁查询显式 Results 映射蛇形数据库列，避免依赖全局驼峰设置。`mine` 用 user_id/created_at 组合索引，日志用 request_id/created_at 索引。没有引入逐个库存查询或新的跨服务写事务。

幂等不是“只靠 Redis 锁”：Redis 防双击与确认重复，Java 请求编号应对明确重放，数据库唯一约束兜底竞争。网络超时后可能实际已提交，原确认机制保守保留已处理状态，提示稍后核实；可通过 mine 查询记录。不能承诺消息和跨系统写入绝对 exactly-once。

## Agent → MCP → Java

- `app/services/after_sale_turn.py#after_sale_intent/after_sale_turn`：轻量中文意图路由；“怎么申请退款”先给政策；“申请退款 + 订单项ID + 原因”才提案。没有原因先问，没有具体订单项先列详情让用户选，不自动选择第一件商品。
- 同一用户的 `afterSaleContext` 通过现有 session_memory_service 保存 orderItemId/type/awaitingReason；后续“原因：质量问题”可接续。显式换订单号时不会沿用旧明细。当前不支持任意自然语言序数指代，也不把口头“确认”当点击确认。
- `app/services/after_sale_service.py#eligibility/check/propose/order_detail`：只用 JavaInternalClient HTTP 读；10秒超时。不使用旧 DB 兼容客户端，不直接连接业务库。资格接口错误或超时不会产生确认提案。
- `app/mcp_server/server.py` 注册新增四个工具；两个已有查询工具继续复用原名称。`mcp_tool_router.py` 注入真实会话 userId，新增工具白名单在 tool_guard.py。
- `propose` 生成固定 requestId 放入 Redis pending；还没有调用 Java create。`agent_runtime.py#finalize_agent_response` 继续调用已有 resolve_action_confirm，生成真实服务端确认卡。
- `app/services/action_execute_service.py#ActionExecuteService.execute` 在用户确认后，用用户 token 发 JSON 调 Java create。只白名单传 requestId/orderItemId/type/reason，不转发 userId 或金额；响应必须是预期的待审核申请。

## 为什么用知识检索，且不强制向量库

项目已有 `app/rag/retriever.py#search_faq`，但依赖 Embedding 的 FAQ 检索在本地关闭向量后无结果。两周轻量方案采用 `knowledge/after_sale_policy.json` 的四个版本化片段，用关键词检索，返回原文、片段ID、版本和适用范围，不让模型自由编造政策。

`after_sale_policy.py#query_policy` 是可解释的本地文本检索；不声称它是向量 RAG 或复杂语义检索。未命中、文件缺失均明确转人工，不请求额外密钥。未来正式政策较多时，可以把审核过的片段导入现有 FAQ 索引并沿用来源字段；不能把互联网上的默认政策当成当前商家政策。

## 状态、审核边界与用户流程

```text
INIT（仅 Redis 提案，用户可取消或等待过期）
   -- 用户点击确认且 Java 重查通过 --> PENDING_REVIEW（业务表）
   -- 未确认/取消/过期 --> 不创建业务申请

后续预留，当前没有自动执行接口：
PENDING_REVIEW -> APPROVED -> COMPLETED
              -> REJECTED
```

例：用户问“订单项 ..._1 能退货退款吗” → 检查资格 → 说明当前能否提交；用户说“申请退货退款 ..._1，原因：收到商品损坏” → 再检查 → 确认卡 → 点击确认 → Java 重新检查 → 保存申请和日志 → “待人工审核，尚未退款”。AI 不保证审核通过。

## 启动与手动验收

本次已执行本地增量建表。对另一台电脑或新数据库，先启动 Docker MySQL，再运行以下命令。脚本不会升级依赖或修改支付配置。

```powershell
cd E:\Simlect-AI-Mall-main\Simlect-AI-Mall-main
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\import-after-sale.ps1
```

本次只改源码和编译测试，没有停止用户正在运行的整套服务，旧进程尚未加载新售后代码。加载新版：

```powershell
.\stop-local.cmd
.\start-local.cmd -Rebuild -WebOnly
```

打开 `http://127.0.0.1:6001/` 并登录。先问“我的订单”，再复制真实订单项ID测试；不能拿商品ID当订单项ID。没有订单时可先按原页面下单。2026-11-02核实：既有本地支付是占位模式，只能创建待付款订单，不能完成模拟付款。未付款订单应被拒绝售后申请；成功申请场景需另行准备经过业务流程的已付款演示订单，不能把占位当付款成功，不能直接改数据库状态。**不要进行真实付款**。建议依次测试：

1. “退款政策是什么？”——应看到 demo-v1、AS片段和演示声明。
2. “订单详情 真实订单号”——只能看到本人订单和明细。
3. “这个订单项能退款吗 真实订单项ID”——返回 Java 判定。
4. “申请退款 真实订单项ID，原因：质量问题”——出现确认卡，点击前不落申请表。
5. 点击取消——不提交；重新提案并点确认——仅待审核，不能显示已退款。

## 18 个验收场景

| # | 场景 | 预期与证据 |
|---|---|---|
| 1 | 我的订单 | 复用 QUERY_ORDERS，按登录用户查询；路由离线测试 |
| 2 | 订单详情 | 先归属查询，越权时不再查明细；Python测试 |
| 3 | 无订单号查物流 | 追问，不编造轨迹；Python测试 |
| 4 | 退款/退货退款/换货咨询 | 正确区分类型；意图参数化测试 |
| 5 | 怎么申请退款 | 咨询先检索政策，不直接提案；Python测试 |
| 6 | 七天无理由/到账时间 | 解释缺资料，不承诺；政策原文测试 |
| 7 | 未收录政策/文件缺失 | 明确未知或暂不可用；Python测试 |
| 8 | 待付款/关闭/已退款 | Java拒绝；状态矩阵测试 |
| 9 | 已退款明细 | Java拒绝；Service测试 |
| 10 | 申请缺原因 | 查询后追问原因，不调用create；Python测试 |
| 11 | 补充原因 | 沿用同用户明细，生成提案；Python测试 |
| 12 | 更换订单号 | 不沿用上笔明细；Python测试 |
| 13 | 资格HTTP超时/畸形结果 | 不生成pending；Python异常测试 |
| 14 | 用户确认 | 只调用申请接口、用用户token、不给金额；Python MockTransport测试 |
| 15 | 重复确认/状态CAS | 原pending确认协议回归测试；不声称真实并发压测 |
| 16 | 同requestId重放/换内容 | 相同返回原记录、不同拒绝；Java及真实MySQL回滚测试 |
| 17 | 同明细已有申请 | 拒绝；Java测试及MySQL唯一约束测试 |
| 18 | 生成确认卡/购物记忆混入 | 售后优先，保留真实卡，不补商品；Graph/卡片/收束测试 |

自动化统计见 `evals/reports/after-sale-summary.json`。测试含模拟与真实本地数据库回滚测试，报告必须区分。未执行新版网页端到端点击、支付资金渠道、审核履约、压力或故障恢复测试，不能写成“完整售后闭环已验收”。

## 面试理解与参考回答

### 1. 为什么增加售后 Agent？

原项目有订单和退款接口，但用户需要自己找订单、判断能否申请；原退款提案直接连接资金执行。新增分支将查订单、解释政策、资格检查和申请补参组织成可解释流程，同时把提交审核与实际退款分开。我的二次开发重点是复用原能力并补齐申请审计，不是重写原商城。

追问：为什么不新建微服务？申请依赖订单归属和状态，两周内放在 Order 中更容易保持事务一致与演示，不增加分布式写入和部署负担。

### 2. AI 为什么不能直接操作退款？

模型生成的文字不能证明身份、订单状态或应退金额，还可能受提示注入影响。它只理解和提出请求。确认前工具只创建用户拥有的提案，确认后 Java 用登录态重新判断，再保存申请。真正资金执行仍是已有 Java Pay/Order 业务逻辑；本分支根本没有调用退款资金接口。

追问：有确认卡就足够安全吗？不够。Java还要校验归属、类型、明细、实时状态、重复请求；客户端确认只是用户意愿，不能代替权限校验。

### 3. 如何防止误操作和重复操作？

缺商品项和原因先追问，咨询不提交；换订单不继承旧目标。Redis 提案绑定用户，有 TTL、锁和 CAS。Java 请求ID校验加数据库唯一约束，申请与日志同事务。确认到执行间状态变化时，Java再次校验可拒绝。网络超时不能简单认为没执行，先查询申请记录。

追问：为什么两层幂等？Redis保护人机交互，数据库保护最终持久化事实，两层处理不同的失败窗口。

### 4. 为什么用 MCP？

MCP统一工具名称、参数及返回协议，让导购和售后复用同一工具层。Graph不关心工具背后 Java 部署细节，MCP也不能越过Java校验。MCP不是鉴权系统，仍需要内部令牌、会话userId覆盖和Java归属检查。

追问：函数调用不就够了？函数调用解决模型选择工具；MCP进一步提供跨进程工具协议。小项目也可直接HTTP，但本项目已有MCP，复用比再引入一套机制更合适。

### 5. 为什么政策要检索？

模型参数中的常识不能代替商家政策。检索能返回有版本、有出处的片段，回答可核对。当前只有四段演示规则，关键词命中比增加向量依赖更易理解和部署；不命中就承认资料不足，绝不补写承诺。

追问：检索命中是否等于有退款资格？不是。政策解释是文本证据，具体订单能否提交仍由Java读取业务状态判断。

### 6. Java承担什么职责？

真实用户身份、订单/明细归属、类型与状态、参数校验、申请记录、幂等与事务日志；未来审核与实际退款也必须由Java执行。Python负责对话编排，不是第二个业务事实数据库。

### 7. LangGraph在哪里？需要多个Agent吗？

原有Graph的agent_loop在导购前调用售后分支，分支按意图选择MCP工具，再进入已有finalize；确认由独立HTTP事件恢复pending。这里的“售后Agent”是有边界的业务工作流，不需要再创建多个自由协商的LLM实例，也不是新增了一个LangGraph interrupt节点。

### 8. 为什么不完全用模型判断意图？

v1选择可预测的中文词组和结构化订单ID补参，避免把“怎么申请”误作写操作，也不额外消耗模型调用。局限是同义表达和英文支持有限；后续可加结构化模型分类，但参数仍经校验、写操作仍需确认。不能把规则路由宣传成训练过的意图识别模型。

### 9. INIT和数据库状态为什么不同？

INIT表示用户还没确认的短期提案，存在Redis；确认成功才落PENDING_REVIEW，避免用户随口咨询产生业务申请。取消提案不等于撤销已经提交的申请。后续审核接口要明确角色、CAS状态条件和审计，不应允许用户提交任意status。

### 10. 你如何证明改造有效？

可以展示源码链路、参数/权限/幂等/状态矩阵测试、真实MySQL回滚测试、确认卡编译检查和现有订单回归结果。仍要诚实说明未做真实资金和新版网页全链路验收，没有线上规模和性能提升百分比。简历可以写“扩展售后咨询与用户确认后的申请工作流”，不能写“实现全自动退款闭环”。


### 后续更新：已补本地模拟付款

2026-11-02新增显式模拟付款按钮及Java入口，补齐之前只能创建待付款订单的缺口。详见 docs/interview/14-本地模拟付款与售后成功路径.md。本地开关默认关闭，本机演示已显式开启；不会发生真实扣款。先重新下单，在支付页点击“模拟付款（不扣款）”，然后测试售后成功申请。
