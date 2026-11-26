package com.simlect.component;

import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.Base64;
import java.util.function.Consumer;

@Slf4j
@Component
public class MqListenerHelper {
    public static final int DEFAULT_MAX_CONSUME_RETRIES=3;
    public static final long CONSUME_IDEMPOTENCY_TTL_STANDARD_SECONDS=24*3600L;
    public static final long CONSUME_IDEMPOTENCY_TTL_HIGH_SECONDS=7*24*3600L;
    public record Delivery(Message message, Channel channel) { }
    @Resource private MqConsumerIdempotencyHelper mqConsumerIdempotencyHelper;

    public void consumeInbox(String consumer, String eventType, Message message, Channel channel, Object payload, Runnable business) {
        consumeInboxBatch(consumer,eventType,List.of(new Delivery(message,channel)),indices -> business.run());
    }
    public void consumeInboxBatch(String consumer, String eventType, List<Delivery> deliveries, Consumer<List<Integer>> business) {
        List<MqConsumerIdempotencyHelper.Outcome> results;
        try {
            results=mqConsumerIdempotencyHelper.executeBatch(consumer,eventType,deliveries.stream().map(Delivery::message).toList(),business);
        } catch (MqConsumerIdempotencyHelper.ProcessingException busy) {
            for (Delivery delivery:deliveries) nack(delivery,true);
            return;
        } catch (Exception failure) {
            // One rolled-back attempt consumes one retry per event, even when a batch contains duplicates.
            var decisions = new HashMap<String,MqConsumerIdempotencyHelper.Outcome>();
            for (Delivery delivery:deliveries) {
                String id = resolveIdempotencyKey(delivery.message());
                String key = id == null || id.isBlank() || id.length() > 256
                        ? "invalid:" + Base64.getEncoder().encodeToString(delivery.message().getBody()) : "id:" + id;
                try {
                    if (!decisions.containsKey(key)) decisions.put(key,failureDecision(delivery,consumer,eventType,failure));
                    settleFailure(delivery,decisions.get(key));
                } catch (Exception databaseFailure) {
                    log.error("MQ_INBOX_UNAVAILABLE consumer={}, requeue without ACK",consumer,databaseFailure);
                    nack(delivery,true);
                }
            }
            return;
        }
        // executeBatch returns only after the local transaction has committed. ACK failures never undo DONE.
        for (int i=0;i<deliveries.size();i++) {
            if (results.get(i)==MqConsumerIdempotencyHelper.Outcome.DONE) ack(deliveries.get(i));
            else nack(deliveries.get(i),false);
        }
    }
    private void failed(Delivery delivery,String consumer,String eventType,Exception failure) {
        try {
            settleFailure(delivery,failureDecision(delivery,consumer,eventType,failure));
        } catch (Exception databaseFailure) {
            log.error("MQ_INBOX_UNAVAILABLE consumer={}, requeue without ACK",consumer,databaseFailure);
            nack(delivery,true);
        }
    }
    private MqConsumerIdempotencyHelper.Outcome failureDecision(Delivery delivery,String consumer,String eventType,Exception failure) {
        var outcome=mqConsumerIdempotencyHelper.recordFailure(consumer,eventType,delivery.message(),failure,DEFAULT_MAX_CONSUME_RETRIES);
        if (outcome==MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED) {
            // Durable Inbox reason + Outbox copy retain the original event identity for manual recovery.
            log.error("MQ_INBOX_DEAD_LETTER consumer={}, eventId={}",consumer,resolveIdempotencyKey(delivery.message()));
        }
        return outcome;
    }
    private void settleFailure(Delivery delivery,MqConsumerIdempotencyHelper.Outcome outcome) {
        if (outcome==MqConsumerIdempotencyHelper.Outcome.DONE) ack(delivery);
        else nack(delivery,outcome!=MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED);
    }
    private void ack(Delivery delivery) {
        try { delivery.channel().basicAck(delivery.message().getMessageProperties().getDeliveryTag(),false); }
        catch (Exception failure) {
            log.warn("MQ ACK failed; committed Inbox will deduplicate redelivery",failure);
            abort(delivery);
        }
    }
    private void nack(Delivery delivery,boolean requeue) {
        try { delivery.channel().basicNack(delivery.message().getMessageProperties().getDeliveryTag(),false,requeue); }
        catch (Exception failure) {
            log.warn("MQ NACK failed; broker redelivery remains eligible",failure);
            abort(delivery);
        }
    }
    private void abort(Delivery delivery) {
        try { delivery.channel().abort(); }
        catch (Exception failure) { log.warn("MQ channel already unavailable",failure); }
    }
    @Deprecated public boolean tryBeginConsume(Message message,long ttlSeconds) { return mqConsumerIdempotencyHelper.tryBeginConsume(message,ttlSeconds); }
    @Deprecated public void releaseConsume(Message message) { mqConsumerIdempotencyHelper.releaseConsume(message); }
    public String resolveIdempotencyKey(Message message) { return mqConsumerIdempotencyHelper.resolveIdempotencyKey(message); }
    public void nackWithRetryOrDlq(Channel channel,long deliveryTag,Message message,String queueName,Object payload,Exception error) throws IOException {
        failed(new Delivery(message,channel),queueName,"LEGACY_CONSUMER",error);
    }
    public void clearConsumeRetry(String queueName,Message message) { /* Durable Inbox history is not cleared after ACK. */ }
}
