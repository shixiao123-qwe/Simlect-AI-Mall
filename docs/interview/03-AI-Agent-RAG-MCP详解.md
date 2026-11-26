# AI Agent、RAG与MCP：结合本项目理解

源码根目录 `E:/Simlect-AI-Mall-main/Simlect-AI-Mall-main`；下文 `app/` 指 `Simlect-backend/Simlect-agent/app/`。

## 1. 一轮请求为什么不只是调用聊天接口

前端通过 `Simlect-front/Simlect-web/src/api/modules.ts` 发送消息，由 `app/api/routes/agent.py`、`services/agent_service.py`处理，后续由图执行工具、保存状态和推送回复。用户身份来自登录会话，不能信任模型传入的userId。

普通LLM擅长理解语言，但不知道本店实时库存。项目把“语言判断”和“业务事实”拆开：模型提取条件，搜索召回候选，Java核验价格/库存，写操作等待用户确认。模型输出的一段自然语言不能替代订单状态变更。

## 2. LangGraph是什么，图怎么运行

`app/graph/builder.py#build_agent_graph` 使用StateGraph注册7节点：entry、build_context、agent_loop、tools、finalize、post_turn、cleanup。正常拓扑是entry → build_context → agent_loop ⇄ tools → finalize → post_turn → cleanup；取消可短路。

`state.py#AgentGraphState` 是TypedDict，不是数据库表。节点返回局部字典由图合并；chunks/tools_called用operator.add追加，其他字段覆盖。新导购在agent_loop首轮调用shopping_turn，返回shopping_handled并直接走finalize，避免重复推理后把严格结果补成热销。

`runner.py#run_agent_graph` 使用userId:messageId构造thread_id，判断是否续跑；finally清理checkpoint。react_round与graph_max_react_rounds限制模型↔工具循环。取消、错误、完成必须走统一清理，不让绑定messageId永久残留。

面试说法：“我用图表示状态和失败出口，并不是用了LangGraph就天然拥有可靠事务。每个工具执行仍要自己做超时、鉴权和幂等。”

## 3. MCP是什么，客户端和服务端在哪里

MCP是工具描述与调用协议。`app/mcp/tools.py#build_mcp_tools` 把普通工具Schema暴露给模型；模型返回tool_calls后，由 `services/mcp_tool_router.py#invoke` 覆盖userId、做白名单与参数映射。

`services/mcp_streamable_client.py` 创建Streamable HTTP会话，先初始化，再call_tool；默认30秒总超时。`:7060/mcp` 的服务实现是 `app/mcp_server/server.py`，独立进程。`/health`是免令牌探活，工具端点用X-Internal-Token。

服务端不是“模型自己访问Java”：server.py注册函数 → shopping_service或mcp_tools_service → JavaInternalClient → Gateway内部接口 → Java领域服务。新RECOMMEND_PRODUCTS和COMPARE_PRODUCTS由受控图分支调用，候选ID来自召回或已展示列表，不让模型直接编出ID。

此处可以用直接HTTP代替MCP；采用MCP的价值是统一工具契约和独立进程边界，代价是会话握手、超时和协议错误处理。项目已有工具框架，因此扩展它比新建另一套工具系统更合适。

## 4. RAG、Embedding、ES和RRF分别做什么

RAG是先检索外部材料再生成回答。`app/rag/embedding.py#embed_text` 把文本转为向量，`retriever.py` 查询FAQ/商品向量，并提供商品关键词召回；业务文档入库同步在Java Search的RabbitMQRagListenerComponent中处理。

关键词适合明确词汇；向量适合语义近似，但不天然理解所有否定条件或预算。`rrf.py#rrf_merge` 对每路第r名加 `1/(RRF_K+r)`，同ID累加后排序，以排名而非不可比分数融合。候选找不到时会影响召回率；不能通过编造商品提高表面召回。

`VECTOR_SEARCH_ENABLED=false`时，retriever的向量分支直接返回空；关键词仍可工作。此环境不使用百炼Embedding，也不能声称已验收向量链路。向量维度必须匹配索引，换模型不能只替换API Key而忽略维度。

## 5. 为什么搜索后还要Java筛选

ES保存的是异步同步的商品索引，minPrice是商品范围信息。用户预算300元，而某个商品的基础规格199、高配399，不能把高配也推荐为300以内。

`shopping_service.py#verified_rows` 显式使用HTTP JavaInternalClient请求 `/internal/product/ai/filter`。Product的AiProductFilterService读取真实SKU、检查上架及属性，再一次批量调用Stock服务；Python偏好排序只在Java合格集合上进行。

这是召回和精排中的业务约束层，不是用LLM判断价格。库存不预留，下单时仍要再次扣减校验。这里没有使用Redis保存“长期可信”的SKU价格/库存结论。

## 6. 结构化输出与Patch不是一回事

`models/shopping.py` 的ShoppingRequirement规定字段类型、金额精度、集合长度、硬属性与软偏好。`shopping_parser.py`用现有模型工厂的with_structured_output结合Pydantic。

Schema让输出可解析，Pydantic让本地类型/区间有约束；它们都不保证语义一定正确。`evals/parser_cases.json`的人工预标注字段用于测真实解析，不把模型生成的答案当作自己的评判标准。

多轮更新由ShoppingPatch的null/clear/reset表示：null是不更新，clear是明确清除，reset是换类重建；合并后再校验。列表特别允许null，防止模型每轮输出默认空列表抹掉“不要二手”。模型异常时不保存错误状态，降级普通搜索并标明条件未核验。

## 7. Prompt和提示注入

本项目有普通客服、意图、压缩等Prompt，以及shopping_parser.PROMPT。Prompt负责说明输出和边界，不能承担最终权限判断。用户文本按JSON数据封装，有助于界定输入，但不是绝对防注入措施。

真正的业务边界在工具白名单、服务端覆盖userId、Java接口鉴权、订单归属检查、状态机和确认流程。模型即使说“忽略预算”也不能直接修改Java库存。新增解析不输出模型生成商品事实。

## 8. 会话记忆与checkpoint的区别

`memory/session_memory_service.py#load/save` 将summary和state存Redis/MySQL，Redis命中优先，未命中读库。shoppingRequirement和shoppingResults使用这个机制；post_turn记录实际展示卡片和历史摘要。

checkpoint是图执行中间状态，由 `graph/checkpoint/redis_saver.py`保存。该实现对pickle载荷加HMAC校验，避免直接反序列化可被篡改的Redis数据。签名保护不等于Redis访问控制，也不保证任意业务都可以安全重放。

当前商品序号对应最近展示列表；比较A/F后，列表只剩两件，再说“第三件”会要求重新选择。这是明确的产品边界，不是支持无限跨轮指代。

## 9. 前端卡片、流式与用户确认

`Simlect-front/Simlect-web/src/stores/agentMessage.ts`、`composables/useAgentSession.ts`接收消息更新；`components/agent/AgentChatItem.vue`根据bizType及PRODUCT_SEARCH_RESULT解析文本和卡片，`AgentProductList.vue`显示商品，`AgentConfirmCard.vue`处理确认。

普通模型输出可以逐片推送；严格推荐先等Java事实完成，再最终推送，不能把尚未核验的价格先流给用户。agent_runtime.finalize_agent_response的shopping_handled分支直接保存严格结果，阻断热销回填。

用户确认调用confirmAction，PendingActionService使用归属校验、锁、Lua CAS；ActionExecuteService经JavaBridge携用户token执行。MCP内部令牌不能充当所有用户的业务授权。

## 10. 失败、日志与可观测性

模型解析、ES、Java、MCP各有时间边界。mcp_streamable_client把协议/连接失败转为用户可读文本；2026-11-02补查发现路由层仍可能把该文本计成success，已增加transport_error分类并停止失败后的结构化回填。MCP错误日志不再打印完整工具响应块，以减少敏感内容泄露。

偏好匹配只做轻量属性证据排序，新增处理“不支持/暂无”等否定线索；这仍不是完整自然语言推理器。比如近义词、定量延迟比较、跨品牌参数标准化，需要后续专门数据与测试，不能因为几个演示案例通过就说完全解决。

## 11. 面试时从哪个例子讲起

用“300元以内蓝牙耳机、不要二手”讲：模型产生结构条件 → 关键词/向量召回ID → MCP调用Java → 按SKU价格、成色和库存过滤 → A/F卡片。再改预算500，保留成色，全新B加入；比较A/B，B有低延迟标注才建议优先考虑，并承认缺实测数值。

被问“准确率多少”时，回答真实报告的样本规模与口径；不要把30条解析字段匹配率、离线测试通过率、端到端推荐满足率混为一个数字。评测入口和限制见evals/README.md。

## 售后规则状态更新（2026-11-04）
以《21-售后Agent规则完善阶段完成报告》为准：期限计算器与核验范围已补充；可靠签收事实、正式运费和到账规则未配置，不能宣称在线七天自动判断。政策为本地词项检索，非已验证向量RAG；换货履约仅设计。真实测试结果见阶段报告。

