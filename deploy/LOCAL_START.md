# Windows 一键启动商城和 AI（16GB 本地演示）

入口是项目根目录的 `start-local.cmd`，停止入口是 `stop-local.cmd`。
此方案接续当前已经初始化过数据库的本地环境。不会重新导入 SQL、清空表或删除 Docker 数据卷。

## 第一次使用

1. 启动 Docker Desktop，等待引擎就绪。
2. 停止 IDEA 里的所有 Java 服务，并在原来启动前端的终端按 Ctrl+C。脚本会自行启动服务，不需要 IDEA 保持打开。
3. 编辑 `deploy/.env.local`（不存在时复制 `.env.local.example`），填写：

   ```dotenv
   LLM_API_KEY=你的DeepSeek密钥
   EMBEDDING_API_KEY=你的阿里云百炼密钥
   ```

   中文内容需要替换为平台生成的真实 Key；无需引号，不能在值后面追加注释。两个密钥通常不是同一个。
   默认 DeepSeek 地址是 `https://api.deepseek.com`，模型沿用项目的 `deepseek-flash`。
   如果你的账号不支持此模型，将 `LLM_MODEL` 改成 DeepSeek 控制台实际可用的模型 ID。
   百炼使用 `text-embedding-v4`，维度固定 1024，与现有 ES 索引一致。
   默认百炼地址是中国内地兼容接口；使用其他地域的 Key 时按平台说明修改 `EMBEDDING_BASE_URL`。
4. 双击 `start-local.cmd`。首次会打包 Java，安装缺少的前端依赖，并创建 Python `.venv`、安装运行依赖，需要联网。
5. 等待出现绿色 `READY`，再打开：

   - 商城：<http://127.0.0.1:6001/>
   - 管理后台：<http://127.0.0.1:6002/admin/>
   - 管理员用户名、密码见 `deploy/.env.local` 中的 `ADMIN_ACCOUNT`、`ADMIN_PASSWORD`，不是商城会员账号。

`.env.local` 已被现有 `.gitignore` 规则忽略。不要分享含真实密钥的文件。
内部令牌为空时首次启动自动生成并保存，统一传给 Java、Agent、MCP；此配置优先于 Agent 原有 `.env` 中同名配置。

### 使用本次提供的 GPT 接口

本机 `deploy/.env.local` 的对话接口已切换为用户提供的中转平台：

```dotenv
LLM_BASE_URL=https://subapi2.beiminge.cn/v1
LLM_MODEL=gpt-6-astra
LLM_USE_RESPONSES_API=true
```

密钥只保存在本机 `.env.local`，此处不展示。该地址是第三方中转平台，不是 OpenAI 官方域名。
Agent 的流式对话和记忆摘要都使用 Responses API；原有 DeepSeek 用户保持 `LLM_USE_RESPONSES_API=false`。
内部工具仍通过 MCP/Java 执行。首次运行启动器会安装支持 Responses 的新版 `langchain-openai`。
Python 的 Base URL 包含 `/v1`；启动器会为 Java Spring AI 自动去掉末尾 `/v1`，避免重复拼接。
此对话 Key 不会自动填入 `EMBEDDING_API_KEY`；阿里云百炼向量密钥仍需单独填写，才能按默认完整方案启动。

## 启动内容与内存

- Docker：MySQL、Redis、RabbitMQ、Nacos、Elasticsearch，沿用当前低内存 Compose 配置。
- Java：User、Product、Stock、Cart、Coupon、Order、Pay、Search、Admin、Gateway 共 10 个服务。物流已在 Order 中，无独立 Logistics 模块需要启动。
- AI：MCP `7060`、Agent `7050`，每个一个进程，不启用 Python 热重载。
- 前端：商城 `6001`、后台 `6002`。

Java 按顺序启动，每个堆上限 256～320MB，启用 `low-memory`、SerialGC、2 个可用 CPU；前端 Node 堆上限 512MB，Rust 线程数 2。Maven 单进程构建，堆上限 512MB。
这些是堆和线程限制，不代表整个进程的实际内存上限；16GB 全部运行仍需留意 Windows 内存占用，建议关闭 IDEA 等较大程序。
AI 使用云端 API，本机不下载或运行大模型；对话与向量接口需要可用额度。Seata 与 Sentinel 控制台不在此本地演示启动范围，`SEATA_ENABLED=false`；本地演示不具备 Seata 跨服务事务保障。

## 常用命令

以下命令在包含 `README.md` 的项目根目录执行，也可以给根目录 `.cmd` 入口传入相同参数。

```powershell
# 只检查配置、工具、端口，不操作 Docker，不安装依赖，不启动服务
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-local.ps1 -CheckOnly

# 启动全部；再次启动前先停止旧进程
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-local.ps1

# 修改 Java 源码后重新打包再启动
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-local.ps1 -Rebuild

# 减少内存：不启动管理前端，后台 Java 服务仍启动
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-local.ps1 -WebOnly

# 暂无 AI 密钥时仅启动商城（AI 功能不可用）
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-local.ps1 -SkipAI

# 停止脚本启动的 Java、AI、前端，保留 Docker 中间件
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\stop-local.ps1

# 同时停止五个 Docker 中间件，保留数据卷
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\stop-local.ps1 -StopMiddleware
```

停止脚本按 PID、启动时间、可执行文件路径核对进程，只终止本启动器记录的进程。Windows 下通过终止进程停止，请等演示操作结束后再执行。不会扫描并杀掉所有 Java/Python/Node 进程。启动失败时会清理本次已启动的应用进程；中间件继续保留，便于排错。强行关闭启动器窗口后，可运行停止脚本清理已记录的进程。

## 日志、邮箱验证码与 AI 验证

每次运行的日志位于 `logs/local-stack/时间戳/`，每个服务有 `.out.log` 和 `.err.log`。

- 发送本地邮箱验证码后，在 `user.out.log` 中搜索 `[本地邮件演示]`，填入对应邮箱最新的六位验证码。有效期 5 分钟，失败后需要重新发送；登录页使用的是页面图片验证码。
- AI 失败查看 `agent.err.log`、`agent.out.log` 和 `mcp.err.log`。
- 端口占用会直接报错，不会擅自关闭 IDEA 或 Windows 的 MySQL/Redis 服务。
- 重启电脑后先打开 Docker Desktop，再双击启动入口。PID 会结合启动时间校验，避免误认重启前的进程。

`READY` 表示服务健康检查和网关到用户服务的路由探测通过，**不表示模型 API Key、额度、模型权限已验证**。脚本不自动发送收费模型请求。
登录商城后打开 AI 助手发送一条问题验证对话；知识库/向量检索还需后台录入知识库并完成向量同步，商品推荐需先录入商品。
支付、地图、内容审核等第三方服务仍按项目文档分别配置；一键启动不能替代这些平台的凭据。

## 环境要求

JDK 17+、Maven、Node.js 20.19+（20.x）或 22.12+、Python 3.11+、Docker Desktop。
找不到命令时，可在 `.env.local` 中填写 `LOCAL_JAVA_EXE`、`LOCAL_MAVEN_CMD`、`LOCAL_NODE_EXE`、`LOCAL_NPM_CMD`、`LOCAL_PYTHON_EXE` 的完整路径。
Python 版本建议 3.11 或 3.12。新虚拟环境依赖安装失败时，脚本会停止，不会显示启动成功。

## 当前本地演示配置

已设置 VECTOR_SEARCH_ENABLED=false，启动不再要求百炼密钥。Agent 跳过 FAQ 和商品向量检索，商品关键词检索及 MCP 订单工具继续使用。Java Search 不启动 RAG 同步消费者、不初始化向量索引；关闭期间的同步消息保留在队列中。启用向量功能时需补齐密钥并重启。首次应用本次 Java 修改请使用 start-local.cmd -Rebuild。


本地启动器设置 MANAGEMENT_HEALTH_SENTINEL_ENABLED=false：Sentinel 控制台未启动时，不参与聚合健康状态；MySQL、Redis 等依赖健康检查继续保留。该修改只需重跑启动器，无需重新打包。


### 本地支付占位（2026-11-02修正）

本地 `deploy/.env.local` 使用 `SIMLECT_PAY_LOCAL_MOCK_ENABLED=true`，启动器现在正确传递此开关。它复用 PayChannel4AliPay 已有占位分支，跳过真实支付宝证书/下单，允许创建待付款订单；不扣款、不自动标记付款成功。生产配置未改变。

Web `.env.development` 的 `VITE_PAY_LOCAL_MOCK_ENABLED=true` 仅用于开发环境展示：支付接口成功且返回payOrderId、但无支付HTML时显示“本地演示订单已创建”，保留正常HTML支付路径与真实错误。该前端开关不能授予权限或改变订单状态。

已验证：仅重启登记的Pay服务；实际内部getPayUrl调用成功（无业务订单写入）；前端5项行为测试及两端支付页SFC编译通过。测试脚本：`Simlect-front/Simlect-web/scripts/check-local-payment.mjs`，在Simlect-web目录执行 `node scripts/check-local-payment.mjs`。

之前将占位模式称为可完成的模拟支付不准确。售后成功申请要求符合资格的已付款订单；待付款订单应拒绝售后。当前不能通过支付页完成模拟付款，不应直接改数据库状态绕过业务校验。


### 后续更新：已补本地模拟付款

2026-11-02新增显式模拟付款按钮及Java入口，补齐之前只能创建待付款订单的缺口。详见 docs/interview/14-本地模拟付款与售后成功路径.md。本地开关默认关闭，本机演示已显式开启；不会发生真实扣款。先重新下单，在支付页点击“模拟付款（不扣款）”，然后测试售后成功申请。


### 售后审核履约入口

本地管理端：http://127.0.0.1:6002/admin/order/afterSale；用户申请进度：http://127.0.0.1:6001/after-sale-requests。需要管理前端时整套启动不要加-WebOnly。审核和退款需要管理员密码换一次性票据；当前模拟资金模式不发生真实资金退回，演示退货地址不得真实寄件。完整说明见docs/interview/15-售后审核履约设计与验收.md。


### 2026-11-03 管理员验证码“网络异常”恢复记录

证据：启动器登记的User进程已退出、8105拒绝连接，Nacos的simlect-user实例列表为空，Gateway报告No servers available for simlect-user；Gateway自身health=UP不能证明业务服务可用。原日志不足以确认User退出原因，不推断为密码错误或验证码格式错误。

只恢复User，更新进程ID/启动时间/可执行路径登记，其他服务保持运行。实测POST三条链路均成功返回data:image图片和checkCodeKey：8105/admin/account/checkCode、8080/admin-api/account/checkCode、6002/api/account/checkCode（带管理员页面Origin）。没有关闭图片验证码。

管理员Account.vue增加显式加载状态、失败后点击重试、失败清除旧挑战及并发点击保护；加载失败不会一直显示“加载中”。scripts/check-login-captcha.mjs从实际SFC函数提取并执行6项行为检查，含成功、空响应、异常、畸形响应和并发；SFC编译通过。测试只生成短期验证码，没有登录或修改账户/订单。

### 2026-11-03 售后页面下游不可用修复

本机 stock 的 hs_err 日志记录 native malloc 内存分配失败；product/pay 进程存在但健康接口不可达、Nacos 无健康注册。Order 日志显示旧退款入口调用 simlect-pay 时无实例。Gateway UP 不代表整条业务链正常。

新增 deploy/repair-local-services.ps1，只恢复指定且已验证 PID、启动时间、可执行文件路径的启动器服务，健康且注册正常的进程保留；未登记的占用端口拒绝操作。修复不启动 Docker、不构建代码、不修改数据库。项目根目录 PowerShell 可执行：

```powershell
.\deploy\repair-local-services.ps1 -Services stock,product,pay,order
.\deploy\repair-local-services.ps1 -Services user,product,stock,order,pay,gateway -CheckOnly
```

共享 Get-LocalJavaArguments 在原堆限制基础上增加 TieredStopAtLevel=1、ReservedCodeCacheSize=64m、Xss512k，限制本地 JVM 原生内存开销。这不能保证系统不会再次内存不足；本次没有进行长期压力验证。

Order 旧直接退款入口增加售后申请检查：按 order -> item 加锁，再以 SELECT FOR UPDATE 当前读查询 active_item_id，发现处理中申请即拒绝，避免绕过审核、退货及验货。经批准履约的可信退款入口保持可用。新增业务拒绝测试和真实 MySQL SQL 测试（夹具事务回滚）。

旧 /after-sale 页面展示已退款订单历史；待寄回申请请看 http://127.0.0.1:6001/after-sale-requests 。旧列表错误后停止滚动自动重试，显式刷新才重试；申请进度错误时保留已加载数据并提示失败，不再显示为暂无申请。

验证：Java 193 项，失败/错误/跳过均为 0；前端 17 项行为检查及 4 个 SFC 编译通过；启动器回归通过。构建日志 logs/after-sale-recovery-tests.log。新 Order JAR 已部署并健康注册；只读实测 6001 -> Gateway -> Order /api/order/afterSale/progress 返回 code=200、2 条记录，最新为 APPROVED / WAIT_RETURN。未执行用户申请审批、寄回、验货或退款。

### 低内存安全测试入口（2026-11-03）

执行 deploy/test-local-memory-safe.ps1 自动暂停本项目登记服务，顺序测试后恢复；-Suite All 包含八模块Java定向回归。不能同时再开全栈IDEA运行。新启动器含物理/提交内存双余量保护。WSL已按用户授权限制5GB，Java堆总量2176MB。实测、命令和限制见 docs/interview/17-本机内存治理与安全测试.md。

### 公开真实商品资料（50件）

本机已添加ABO许可商品资料与本地图片。商城搜索“公开样本”“厨具”“办公用品”查看。价格、库存及默认SKU为本地测试值，来源属性不补造。原DEMOAI夹具与订单保留。重新导入使用deploy/import-public-catalog.ps1 -SyncSearch（会刷新Product），详见docs/interview/18-公开商品样本导入与验收.md。

### 当前运行配置更新（2026-11-03）
WSL 内存上限已由 5GB 调整为 4GB，2 核及 2GB swap 保留。Docker 已切换至核验后的旧数据盘副本；不要删除 local-data/docker-recovery-20261103。恢复及备份说明见 docs/interview/20-Docker旧数据恢复与备份.md。备份命令：powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\backup-local-data.ps1。


### 平衡运行策略（2026-11-04）

根据用户最终选择，4GB 为可用物理内存目标，1GB 为硬保护线。低于目标提示警告但不阻止运行；低于 1024MB 或提交余量不足才拒绝继续。整栈启动前仍要求物理 1536MB、提交 7168MB，以便为后续服务留空间；分时测试要求物理 1536MB、提交 3072MB。每个服务启动前、健康就绪后均检查；失败回收本轮启动进程。恢复脚本就绪后不达标则回收新恢复的服务。

watch-local-memory.ps1 的 ReserveOK 表示至少 1GB 物理及 2GB 提交余量，TargetOK 单独表示达到 4GB 物理目标。检查是采样结果，不是持续保留或自动内存回收。

离线启动器回归通过：1023MB 拒绝、1024MB 可通过、3072MB 仅提示目标不足、提交不足拒绝。数据库、WSL 配置及其他用户程序未修改。
实际启动验收：2026-11-04，10个Java服务、MCP、Agent、web全部READY。六次间隔5秒采样，最低物理余量4273MB、提交余量4489MB，ReserveOK与TargetOK均为true；这是短时采样，不是持续保证。报告 logs/balanced-memory-20261104.json，启动日志 logs/restart-balanced-20261104.log。


### 2026-11-24 测试场景与端口

现有系统MYSQL80保留运行，商城本地MYSQL_PORT=3307。启动器自动加载docker-compose.local-port.yml，Java/Python统一连接商城数据库；原数据卷保留，默认部署仍3306。请勿把MYSQL80的数据当商城数据或重复导入。

导购测试：start-local.cmd -Scenario guide -WebOnly（五个Java服务及AI，订单/购物车/售后不在该模式保证范围）。交易售后：start-local.cmd -Scenario after-sale；完整演示：start-local.cmd -Scenario full。切换前使用stop-local.cmd，避免重复占用端口。分时回归：deploy/test-local-memory-safe.ps1 -Suite Reliability。

本机旧D:/anc基础Python缺标准库，venv已备份并改用现有同版本D:/python，LOCAL_PYTHON_EXE亦已修正，未升级依赖。详细代码、回归与在线验证状态见docs/interview/24-数据分页与AI工具可靠性改造报告.md。
