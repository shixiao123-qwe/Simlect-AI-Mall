# 16GB 本地开发配置

此配置适合少量测试数据、单用户演示。内存上限不等于实际常驻占用，也不能保证整套服务在所有 16GB 电脑上稳定全开。

## Docker 中间件

当前 Compose 中五个基础服务的容器内存上限合计 3968MiB，之前为 5760MiB。

| 服务 | 容器限制 | 内部设置 |
| --- | --- | --- |
| MySQL | 768MiB | buffer pool 128MiB，最多 150 个连接 |
| Redis | 384MiB | maxmemory 256MiB |
| RabbitMQ | 768MiB | 2 个 Erlang 调度器，内存高水位 60% |
| Nacos | 768MiB | Java 堆 128～384MiB，新生代 128MiB（覆盖镜像默认 512MiB） |
| Elasticsearch | 1280MiB | Java 堆 512MiB |

Redis 沿用原有 allkeys-lru 策略，达到较小的 maxmemory 后可能更早淘汰键，含会话和待确认提案；此配置不用于负载测试或生产。RabbitMQ 超过高水位会暂停发布以施加背压。发生容器 OOM 时应减少同时运行的组件或提高该组件限制。

先在 IDEA 停止本项目 Java 服务，再从项目根目录应用配置（保留已有数据卷）：

```powershell
docker compose -f .\deploy\docker-compose.middleware.yml up -d mysql redis rabbitmq nacos elasticsearch
docker compose -f .\deploy\docker-compose.middleware.yml ps
docker stats --no-stream
```

Sentinel 和 Seata 本轮不启动。不要执行 `down -v`，这会删除数据卷。

## Java 服务

重新构建一次，使公共模块修改生效：

```powershell
cd Simlect-backend
mvn install "-DskipTests"
```

IDEA 每个服务的环境变量追加 `SPRING_PROFILES_ACTIVE=low-memory`，保留已有数据库、内部令牌、管理员密码等配置，以及 `SEATA_ENABLED=false`。

该 profile 将 Hikari 连接池改为最少 1、最多 5；Tomcat 线程改为最少 2、最多 30；Spring 异步执行器改为 2～4 个线程和 100 个排队任务。Redisson 使用 2 个业务线程、2 个 Netty 线程，普通连接池最少 1、最多 8，订阅连接池最少 1、最多 4。关闭 profile 后使用原有默认值。应用显式创建的其他线程池不受这些设置控制。

每个服务的 VM options 使用：

```text
-Xms64m -Xmx256m -XX:+UseSerialGC -XX:ActiveProcessorCount=2
```

User、Product、Order、Search 可将 `-Xmx256m` 改成 `-Xmx320m`。堆外内存、线程栈、类元数据仍会占内存，Xmx 不是整个进程的内存上限。不要把这些复杂服务统一压到 128MiB。

## 前端

先停止 Java 服务完成首次依赖安装/预打包。项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\start-frontend-low-memory.ps1 -Target web
# 管理端按需在另一个终端运行：
powershell -ExecutionPolicy Bypass -File .\deploy\start-frontend-low-memory.ps1 -Target admin
```

脚本仅对子进程限制 Node 老生代堆为 512MiB、Rust Rayon 线程为 2；不能限制全部原生内存，不能保证解决系统提交内存耗尽。退出后恢复当前进程原有环境变量。

优先只开一个前端，逐个启动后端并观察任务管理器。如果仍出现原生内存分配失败，检查 Windows 的“已提交”内存和分页文件是否为系统管理，减少并行服务；不要只提高 Node 堆限制。IDEA 自身占用也需计入预算，可尝试最大堆 1024～1536MiB，索引大型项目可能变慢。
