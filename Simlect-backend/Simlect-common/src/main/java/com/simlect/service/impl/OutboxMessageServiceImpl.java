package com.simlect.service.impl;

import com.simlect.constants.ReliableMessageSender;
import com.simlect.utils.JsonUtils;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.entity.enums.OutboxMessageStatusEnum;
import com.simlect.entity.po.LocalMessageOutbox;
import com.simlect.mappers.LocalMessageOutboxMapper;
import com.simlect.service.OutboxMessageService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class OutboxMessageServiceImpl implements OutboxMessageService {

    @Resource
    private LocalMessageOutboxMapper localMessageOutboxMapper;
    @Lazy
    @Resource
    private ReliableMessageSender reliableMessageSender;

    // A fresh token for every claim fences older attempts, including attempts on this JVM.
    private final String instanceId = UUID.randomUUID().toString().replace("-", "");
    @Value("${mq.outbox.lease-seconds:60}")
    private int leaseSeconds = 60;
    @Value("${mq.outbox.dispatch-max-retries:10}")
    private int maxAttempts = 10;
    @Value("${mq.outbox.backoff-base-seconds:2}")
    private int backoffBaseSeconds = 2;
    @Value("${mq.outbox.backoff-max-seconds:300}")
    private int backoffMaxSeconds = 300;

    @PostConstruct
    public void validateSettings() {
        if (leaseSeconds < 1 || leaseSeconds > 3600 || maxAttempts < 1 || maxAttempts > 1000
                || backoffBaseSeconds < 1 || backoffMaxSeconds < backoffBaseSeconds || backoffMaxSeconds > 86400) {
            throw new IllegalArgumentException("Invalid Outbox lease/retry/backoff settings");
        }
    }

    @Override
    public Long savePending(String exchange, String routingKey, Object payload,
                            String idempotencyKey, MessageReliabilityLevelEnum reliabilityLevel) {
        if (StringTools.isEmpty(idempotencyKey) || StringTools.isEmpty(exchange) || StringTools.isEmpty(routingKey)) {
            throw new IllegalArgumentException("outbox 参数不完整");
        }
        LocalMessageOutbox existing = localMessageOutboxMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return existing.getId();
        }
        Date now = new Date();
        LocalMessageOutbox row = new LocalMessageOutbox();
        row.setIdempotencyKey(idempotencyKey);
        row.setExchangeName(exchange);
        row.setRoutingKey(routingKey);
        row.setPayloadJson(JsonUtils.toJson(payload));
        row.setReliabilityLevel(reliabilityLevel == null
                ? MessageReliabilityLevelEnum.STANDARD.getCode()
                : reliabilityLevel.getCode());
        row.setStatus(OutboxMessageStatusEnum.PENDING.getStatus());
        row.setRetryCount(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            localMessageOutboxMapper.insert(row);
            return row.getId();
        } catch (DuplicateKeyException dup) {
            LocalMessageOutbox again = localMessageOutboxMapper.selectByIdempotencyKey(idempotencyKey);
            return again == null ? null : again.getId();
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tryDispatch(Long id) {
        dispatch(id, maxAttempts);
    }

    private boolean dispatch(Long id, int attemptLimit) {
        if (id == null) return false;
        String owner = instanceId + ":" + UUID.randomUUID().toString().replace("-", "");
        if (!Integer.valueOf(1).equals(localMessageOutboxMapper.claimLease(id, owner, leaseSeconds,
                attemptLimit, backoffBaseSeconds, backoffMaxSeconds))) return false;
        // Claim is an autocommit conditional UPDATE, not an uncommitted lock around broker IO.
        LocalMessageOutbox row = localMessageOutboxMapper.selectById(id);
        if (row == null || !owner.equals(row.getLeaseOwner())
                || !Integer.valueOf(1).equals(localMessageOutboxMapper.ownsLease(id, owner))) return false;
        try {
            reliableMessageSender.sendOutboxWithConfirm(row.getExchangeName(), row.getRoutingKey(),
                    JsonUtils.parse(row.getPayloadJson()), row.getIdempotencyKey(), owner);
        } catch (Exception error) {
            log.warn("Outbox publish failed id={}, attempt={}", id, row.getRetryCount(), error);
            finish(id, owner, false, truncate(error.getMessage()));
            if (row.getRetryCount() != null && row.getRetryCount() >= attemptLimit) {
                log.error("OUTBOX_RETRIES_EXHAUSTED id={}, key={}, manual review required", id, row.getIdempotencyKey());
            }
            return false;
        }
        // A failed SENT write leaves a leased SENDING row. Recovery republishes with the SAME business key.
        return finish(id, owner, true, null);
    }

    private boolean finish(Long id, String owner, boolean sent, String error) {
        try {
            if (Integer.valueOf(1).equals(localMessageOutboxMapper.completeLease(id, owner, sent, error,
                    backoffBaseSeconds, backoffMaxSeconds))) return sent;
            log.warn("Outbox result ignored after lease loss id={}, sent={}", id, sent);
        } catch (Exception databaseError) {
            log.error("OUTBOX_RESULT_UNKNOWN id={}, sent={}, lease recovery/manual review required", id, sent, databaseError);
        }
        return false;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int dispatchPendingBatch(int batchSize, int maxRetries) {
        int limit = batchSize <= 0 ? 20 : Math.min(batchSize, 1000);
        int attemptLimit = maxRetries <= 0 ? maxAttempts : Math.min(maxAttempts, maxRetries);
        int exhausted = localMessageOutboxMapper.expireExhausted(limit, attemptLimit, leaseSeconds);
        if (exhausted > 0) log.error("OUTBOX_RETRIES_EXHAUSTED expired leases={}, manual review required", exhausted);
        List<LocalMessageOutbox> rows = localMessageOutboxMapper.selectReadyBatch(limit, attemptLimit, leaseSeconds);
        if (rows == null) return 0;
        int sent = 0;
        for (LocalMessageOutbox row : rows) {
            try {
                if (dispatch(row.getId(), attemptLimit)) sent++;
            } catch (Exception error) {
                log.error("Outbox dispatch unavailable id={}", row.getId(), error);
            }
        }
        return sent;
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
