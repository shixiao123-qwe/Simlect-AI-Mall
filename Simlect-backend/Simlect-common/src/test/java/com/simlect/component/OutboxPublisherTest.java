package com.simlect.component;

import com.simlect.constants.ReliableMessageSender;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OutboxPublisherTest {
    @Test void outboxNeverTreatsRedisSendGateAsPublisherConfirmation() throws Exception {
        var sender = new ReliableMessageSender();
        var helper = mock(MqPublisherConfirmHelper.class);
        var guard = mock(MqIdempotencyGuard.class);
        ReflectionTestUtils.setField(sender, "mqPublisherConfirmHelper", helper);
        ReflectionTestUtils.setField(sender, "mqIdempotencyGuard", guard);
        sender.sendOutboxWithConfirm("ex", "route", "body", "business-key", "attempt-1");
        sender.sendOutboxWithConfirm("ex", "route", "body", "business-key", "attempt-2");
        verifyNoInteractions(guard);
        var processor = org.mockito.ArgumentCaptor.forClass(MessagePostProcessor.class);
        var attempts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(helper, times(2)).sendAndAwaitConfirm(eq("ex"), eq("route"), eq("body"), processor.capture(), attempts.capture());
        assertEquals(java.util.List.of("attempt-1", "attempt-2"), attempts.getAllValues());
        for (var value : processor.getAllValues()) {
            Message message = value.postProcessMessage(new Message(new byte[0], new MessageProperties()));
            assertEquals("business-key", message.getMessageProperties().getMessageId());
            assertEquals("business-key", message.getMessageProperties().getHeader(MqConsumerIdempotencyHelper.HEADER_IDEMPOTENCY_KEY));
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
        }
    }
    @Test void duplicateStableKeysRequireTransactionalInboxRatherThanRedisFlag() {
        var guard = new MqConsumerIdempotencyHelper();
        var props = new MessageProperties(); props.setMessageId("same-business-key");
        assertEquals("same-business-key",guard.resolveIdempotencyKey(new Message(new byte[0],props)));
        assertThrows(IllegalStateException.class,()->guard.tryBeginConsume(new Message(new byte[0],props),3600));
    }
}
