package com.simlect.task;

import com.simlect.service.OutboxMessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@ConditionalOnProperty(name = "mq.outbox.dispatch-enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.common-scheduling.enabled", havingValue = "true")
/**
 * 【功能】Outbox 定时补投：扫描 PENDING/FAILED 和过期 SENDING 消息再次 dispatch。
 * <p>
 * 【角色】可靠投递的扫表重试；与 TransactionalMqSender.afterCommit 即时投递互补。
 * 数据库行租约防止多实例同时有效领取。
 * <p>
 * 【调用链】@Scheduled → DB lease → OutboxMessageService.dispatchPendingBatch → RabbitMQ。
 */
public class OutboxDispatchTask {

    @Resource
    private OutboxMessageService outboxMessageService;

    @Value("${mq.outbox.dispatch-batch-size:30}")
    private int batchSize;

    @Value("${mq.outbox.dispatch-max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${mq.outbox.dispatch-interval-ms:5000}")
    public void dispatch() {
        try {
            int count = outboxMessageService.dispatchPendingBatch(batchSize, maxRetries);
            if (count > 0) {
                log.info("Outbox 定时投递成功 {} 条", count);
            }
        } catch (Exception e) {
            log.error("OUTBOX_SCAN_FAILED check schema migration and database availability", e);
        }
    }
}
