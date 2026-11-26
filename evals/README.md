# 导购 2.0 评测

## 如何运行

在项目根目录运行（Windows）：

测试环境除 Agent 运行依赖外，需 `python -m pip install -r evals/requirements-test.txt`。Windows受限沙箱可能阻止asyncio本机事件循环，应在正常本机终端执行。原项目个别测试的缓存目录访问受限时，可加 `-p no:cacheprovider`，不影响测试逻辑。

```powershell
.\Simlect-backend\Simlect-agent\.venv\Scripts\python.exe evals/run.py
```

这是离线契约/故障注入测试，不访问真实数据库、MCP 或付费模型。结果保存在 `reports/offline.json` 和 `reports/offline.xml`。覆盖预算类型/范围、非法字段、品类、多轮更新/清除/重置、JSON 错误、推荐空结果、参数缺失、序号比较、Java 异常、MCP 超时、严格结果不补热销、非购物意图不被覆盖。

真实模型评测（会产生API用量）：

```powershell
.\Simlect-backend\Simlect-agent\.venv\Scripts\python.exe evals/live_parser.py
```

读取已忽略的 `deploy/.env.local`，只导入模型相关设置。30 条合成中文需求及预标注结果在 `parser_cases.json`。原始解析输出、预期和每条耗时保存在 `reports/live-parser.json`，不保存密钥。

## 已执行结果（2026-11-01）

- 首轮离线 49 条全部通过。耗时见机器生成报告；它是单元/契约测试耗时，不是线上响应耗时。
- 真实解析 30/30 通过，按 expected 指定字段全匹配计分。平均 3937.35ms，最近秩法 P95 为 5560.22ms。失败分类为空。
- 此 30 条数据覆盖5类品类、预算、保留偏好的多轮预算修改、全新硬约束；不是复杂自然语言总体准确率，也不证明库存、商品推荐或工具端到端成功。
- 无改造前同数据集实测基线，不能声称提升了某个百分比。

## Java 权威筛选测试

```powershell
cd Simlect-backend
mvn.cmd -o -pl Simlect-product/app,Simlect-stock/app -am test '-Dtest=AiFilterControllerTest,AiProductFilterServiceTest,StockReadBatchTest,StockInternalControllerTest,ProductInternalControllerTest,SkuStockServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' --no-transfer-progress
```

Java 测试使用 Mockito 控制 SKU、库存返回值，验证预算边界、下架、缺失库存、属性未知、库存服务失败、反向预算、重复批量ID，不是正在运行的商城的真实业务评测。AiProductFilterServiceTest 为8条、StockReadBatchTest为2条；同时运行原有相关回归。

最终Java运行53条通过，Product 17条、Stock 36条，摘要见 `reports/java.json`。新增AiFilterControllerTest另外验证Bean Validation参数拒绝与合法请求转发。

已有 Agent 图、商品搜索回填/相关性/咨询、模型工厂与新增49条评测合跑结果：74 passed。首次原异步测试因缺少pytest-asyncio失败，补齐测试插件后重跑通过；未因此升级运行依赖。

## 指标边界

| 指标 | 本次能报告的内容 |
|---|---|
| 结构化需求准确率 | 30条真实LLM解析的预标注字段匹配率，原始输出可追溯 |
| 硬约束满足率 | Java固定夹具测试已覆盖；真实商品数据端到端比率尚未采样，报告为null |
| MCP成功率 | 离线异常路径已测；真实网络工具调用成功率尚未采样，报告为null |
| 平均/P95 | 真实模型解析往返耗时与离线测试耗时分别统计，不能混用 |
| 失败分类 | live-parser报告记录字段不匹配或异常类型；offline报告记录契约失败 |

下一轮应准备有上/下架、价格多档、零库存、未知成色属性的演示数据，启动新版 Stock/Product/MCP/Agent 后采样真实推荐和对比；不能仅凭Mock测试得出线上硬约束满足率100%。

## 2026-11-02补充：固定目录真实链路已执行

前述“尚未采样”是首次评测的状态；现在已导入6款合成商品，并增加 `live_catalog.py`：

```powershell
.\Simlect-backend\Simlect-agent\.venv\Scripts\python.exe evals/live_catalog.py
```

该程序先检查固定演示SKU价格和库存是否仍与标注一致；数据被下单/手动修改后会拒绝继续，不能为了跑分自动重置库存。前置库存检查直连Stock8102并携内部令牌，因为项目没有网关Stock内部路由。推荐本身经真实MCP→Gateway→Product→Feign Stock，不直接读库。

20条预算/成色组合真实调用均通过，MCP传输成功率20/20；37张返回商品卡片中37张满足标注硬条件。平均10241.97ms，P95 13439.81ms；这个耗时包含MCP会话及Java调用，不含LLM解析。原始结果 `reports/live-catalog.json`。

当前离线评测已增至57条通过；最终合跑原Python定向回归为82条通过。业务侧另执行购物车、订单、超时关单、支付渠道现有定向回归184条，通过结果见 `reports/business-regression.json`。这些不属于个人全部新增测试数量。

固定小目录和合成测试不能外推生产准确率、真实支付和高并发；没有旧版本同条件基线。

## 收尾验收

运行真实只读检查：`python evals/closing_live.py`（使用项目现有虚拟环境）。返回非零表示有检查未通过；使用本机已登录演示会话，不创建订单或执行支付退款。报告 `reports/closing-live.json`，首次内存故障和中间恢复报告保留独立文件。自动化、人工截图与未验证能力分开记录，详见 `docs/interview/16-收尾验收报告.md`。不要在全栈运行时并行执行 Maven 与前端构建来跑分；本机已实测出现系统提交内存不足。

## 2026-11-24 业务流程与在线只读评测

`test_business_workflows.py` 执行实际业务分支/路由代码，LLM、MCP传输、ES、Java和Redis为受控边界；不属于真实线上准确率。运行 `python evals/run_business_flows.py` 可生成独立JUnit与summary；也可用 `--junit-input 路径` 汇总现有合并回归，避免重复执行。

启动 `start-local.cmd -Scenario guide -WebOnly` 后，可运行 `python evals/live_business_readonly.py`，真实通过MCP/Java验证推荐、改预算、对比、空结果、政策限制和首页分页。默认解析为脚本化条件；`--with-model` 才调用实际模型并可能产生费用。会话使用隔离内存，不写实际用户会话或订单。此入口不覆盖浏览器传输、完整Graph持久化及真实交易。具体测试证据见 docs/interview/24-数据分页与AI工具可靠性改造报告.md。
