package com.simlect.component;

import com.simlect.constants.RabbitMQConfig;
import com.simlect.constants.TransactionalMqSender;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.mappers.MqInboxMapper;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

@Component
public class MqConsumerIdempotencyHelper {
    public static final String HEADER_IDEMPOTENCY_KEY = "x-idempotency-key";
    public enum Outcome { DONE, DEAD_LETTERED }
    public static class ProcessingException extends RuntimeException {
        public ProcessingException() { super("Inbox event is still PROCESSING; do not ACK"); }
    }
    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String reason) { super(reason); }
    }
    @Resource private MqInboxMapper inboxMapper;
    @Resource private PlatformTransactionManager transactionManager;
    @Resource private TransactionalMqSender transactionalMqSender;

    /** Old boolean protocol cannot prove a committed business outcome. All production callers use executeBatch. */
    @Deprecated
    public boolean tryBeginConsume(Message message, long ttlSeconds) {
        throw new IllegalStateException("Use transactional Inbox consumption; a Redis flag is not DONE");
    }
    @Deprecated
    public void releaseConsume(Message message) { /* No Redis business-completion marker remains. */ }

    private TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    public List<Outcome> executeBatch(String consumer, String eventType, List<Message> messages,
                                      Consumer<List<Integer>> business) {
        validateConsumer(consumer, eventType);
        List<String> ids = messages.stream().map(this::requiredEventId).toList();
        return transaction().execute(status -> {
            var outcomes = new ArrayList<>(Collections.nCopies(messages.size(), Outcome.DONE));
            // Stable lock ordering also handles duplicate events inside a notification batch.
            var firstIndex = new TreeMap<String,Integer>();
            for (int i=0; i<ids.size(); i++) firstIndex.putIfAbsent(ids.get(i),i);
            var claimed = new ArrayList<Integer>();
            for (var entry : firstIndex.entrySet()) {
                String id = entry.getKey();
                int inserted = inboxMapper.insertProcessing(consumer,id,eventType);
                Map<String,Object> row = inboxMapper.lock(consumer,id);
                if (row == null) throw new IllegalStateException("Inbox row missing after insert");
                if ("DONE".equals(row.get("status"))) continue;
                if (isDeadLettered(row)) {
                    for (int i=0;i<ids.size();i++) if (ids.get(i).equals(id)) outcomes.set(i,Outcome.DEAD_LETTERED);
                    continue;
                }
                if (inserted != 1 && inboxMapper.begin(consumer,id) != 1) throw new ProcessingException();
                claimed.add(entry.getValue());
            }
            if (!claimed.isEmpty()) business.accept(List.copyOf(claimed));
            for (Integer index : claimed) {
                if (inboxMapper.complete(consumer,ids.get(index)) != 1) throw new IllegalStateException("Inbox completion rejected");
            }
            return outcomes;
        });
    }

    /** Called only after business rollback. FAILED + DLQ Outbox are committed in a separate local transaction. */
    public Outcome recordFailure(String consumer, String eventType, Message message, Exception error, int maxRetries) {
        validateConsumer(consumer,eventType);
        String original = resolveIdempotencyKey(message);
        boolean invalid = original == null || original.isBlank() || original.length() > 256;
        String id = invalid ? "invalid:" + digest(consumer + ":" + Base64.getEncoder().encodeToString(message.getBody())) : original;
        return transaction().execute(status -> {
            inboxMapper.insertFailed(consumer,id,eventType);
            Map<String,Object> row = inboxMapper.lock(consumer,id);
            if (row == null) throw new IllegalStateException("Inbox failure row missing");
            if ("DONE".equals(row.get("status"))) return Outcome.DONE;
            if (isDeadLettered(row)) return Outcome.DEAD_LETTERED;
            if (!"FAILED".equals(row.get("status"))) throw new ProcessingException();
            String reason = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            if (inboxMapper.fail(consumer,id,reason.substring(0,Math.min(500,reason.length()))) != 1)
                throw new IllegalStateException("Inbox failure update rejected");
            int failures = ((Number) row.get("failureCount")).intValue() + 1;
            if (!invalid && !(error instanceof InvalidEventException) && failures <= maxRetries) return null;
            var letter = new LinkedHashMap<String,Object>();
            letter.put("consumerName",consumer); letter.put("eventId",id); letter.put("eventType",eventType);
            letter.put("failureCount",failures); letter.put("reason",reason);
            letter.put("bodyBase64",Base64.getEncoder().encodeToString(message.getBody()));
            letter.put("originalMessageId",message.getMessageProperties().getMessageId());
            letter.put("contentType",message.getMessageProperties().getContentType());
            transactionalMqSender.sendAfterCommit(RabbitMQConfig.INBOX_FAILURE_EXCHANGE,RabbitMQConfig.INBOX_FAILURE_KEY,
                    letter,"inbox-dlq:" + digest(consumer + "\u0000" + id),MessageReliabilityLevelEnum.STANDARD);
            if (inboxMapper.markDeadLettered(consumer,id) != 1) throw new IllegalStateException("Inbox DLQ update rejected");
            return Outcome.DEAD_LETTERED;
        });
    }

    private boolean isDeadLettered(Map<String,Object> row) {
        Object value=row.get("dlqEnqueued");
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()==1;
    }
    private void validateConsumer(String consumer, String eventType) {
        if (consumer==null || consumer.isBlank() || consumer.length()>128 || eventType==null || eventType.isBlank() || eventType.length()>128)
            throw new IllegalArgumentException("Invalid Inbox consumer/event type");
    }
    private String requiredEventId(Message message) {
        String id=resolveIdempotencyKey(message);
        if (id==null || id.isBlank() || id.length()>256) throw new InvalidEventException("Stable event ID required; business not executed");
        return id;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public String resolveIdempotencyKey(Message message) {
        if (message==null || message.getMessageProperties()==null) return null;
        Object header=message.getMessageProperties().getHeader(HEADER_IDEMPOTENCY_KEY);
        if (header!=null && !StringTools.isEmpty(String.valueOf(header))) return String.valueOf(header);
        return message.getMessageProperties().getMessageId();
    }
}
