# Docker 旧数据恢复与备份（2026-11-03）

## 本次发生了什么

Docker Desktop 日志记录了 2026-11-03 15:49 左右的恢复出厂设置操作。之后启动的是 C 盘新建的空数据盘，所以界面中没有原容器和镜像。并非仅仅容器停止。

在 E:/wsl/DockerData/DockerDesktopWSL/disk/docker_data.vhdx 找到了原数据盘，大小 7,255,097,344 字节，最后修改时间 15:49。恢复过程先停止 Docker，再复制旧盘并核对 SHA256，最后让 Docker 使用副本。没有格式化原盘，也没有重新初始化业务数据库。

原盘与初始副本 SHA256：ECA18DB6D05555C85F567F1182CDDE69FA72939CBB6FE3A6932E80A5FA31F260。

当前正在使用的数据盘位置：

`E:/Simlect-AI-Mall-main/Simlect-AI-Mall-main/local-data/docker-recovery-20261103/wsl/disk/docker_data.vhdx`

这是运行中的数据盘，不能随意移动或删除；运行后内容会变化，初始哈希不再用于核对当前活动盘。原始 E:/wsl 路径的旧盘保留作为恢复前副本。Docker settings-store.json 已备份，CustomWslDistroDir 指向副本所在的 wsl 目录。

## 恢复结果

恢复五个商城中间件容器、镜像及 MySQL、Redis、RabbitMQ、Elasticsearch 四个持久卷。历史无关 my-redis 保持停止。

SQL 只读核对：账号 1 个、订单 3 笔、DEMOAI 商品 6 件、ABO 公开商品 50 件、售后申请 2 条，其中完成 1 条。模拟退款记录保留；没有发生真实资金操作。证据在 logs/docker-restored-counts.tsv 和 logs/docker-disk-recovery.json。

恢复启动了 10 个 Java 服务、MCP、Agent 与商城前端。只读验收 42/42；公开商品检查 104/104；真实 MCP 推荐和对比通过。以上不等于真实模型调用验收，也不等于长期压力测试。

WSL 当前限制 4GB、2 核、2GB swap，原配置另存备份。12 次、间隔 5 秒的全栈内存采样，最低物理余量 2004MB、提交余量 2213MB，预留检查通过。采样只能反映该时段，不能保证打开更多程序后仍有相同余量。报告：logs/shopping-studio-memory.json。

## 日常使用与备份

打开 http://127.0.0.1:6001/ 。重启后在项目内层根目录运行 start-local.cmd；需要重新构建 Java 时才使用 -Rebuild。不要重复启动 IDEA 中的同一批服务。

在 E:/Simlect-AI-Mall-main/Simlect-AI-Mall-main 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\backup-local-data.ps1
```

脚本以 mysqldump 的 single-transaction 导出 12 个数据库，并生成文件大小和 SHA256 清单；不修改业务数据。备份包括账号和订单等私有信息，不应上传 GitHub。backups、local-data 和 vhdx 已加入忽略规则。

最新备份：backups/mysql/20261103-164416-110/simlect-databases.sql。已验证导出成功并生成校验清单；尚未在独立 MySQL 实例执行恢复演练。这是 MySQL 逻辑备份，不包含商品上传文件、ES/Redis/RabbitMQ 数据或运行环境；图片及上传目录应另行保留。

Docker 正常使用时不要点击恢复出厂设置或删除卷。再次出现空部署，先核对实际数据盘路径；不要急着重导 SQL，以免将新旧数据混淆。

## 之前的启动错误

两个弹窗分别指向 dockerInference 和 docker-secrets-engine/engine.sock。处理时只对已停止 Docker 的零字节运行时套接字目录改名留存，没有删除数据库卷。类似 Windows AF_UNIX 套接字问题的上游记录：

- https://github.com/docker/desktop-feedback/issues/554
- https://github.com/docker/desktop-feedback/issues/460

本次数据恢复的关键是找到旧数据盘并使用核验后的副本，不是删除套接字能恢复数据库。
