package com.simlect.component;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MqListenerHelperTest {
    MqConsumerIdempotencyHelper inbox;
    MqListenerHelper helper;
    Channel channel;
    Message message;
    @BeforeEach void setup() {
        inbox=mock(MqConsumerIdempotencyHelper.class);channel=mock(Channel.class);
        helper=new MqListenerHelper();ReflectionTestUtils.setField(helper,"mqConsumerIdempotencyHelper",inbox);
        var props=new MessageProperties();props.setMessageId("m-1");props.setDeliveryTag(7);
        message=new Message(new byte[]{1},props);
    }
    void consume() { helper.consumeInbox("pay.queue","PAY",message,channel,"payload",()->{}); }
    void succeeds() { when(inbox.executeBatch(anyString(),anyString(),anyList(),any())).thenReturn(List.of(MqConsumerIdempotencyHelper.Outcome.DONE)); }
    void fails() { when(inbox.executeBatch(anyString(),anyString(),anyList(),any())).thenThrow(new IllegalStateException("business failed")); }
    @Test void doneMeansCommittedAndCanAck() throws Exception {
        succeeds();consume();verify(channel).basicAck(7,false);
        verify(inbox,never()).recordFailure(anyString(),anyString(),any(),any(),anyInt());
    }
    @Test void processingIsRequeuedNeverAcked() throws Exception {
        when(inbox.executeBatch(anyString(),anyString(),anyList(),any())).thenThrow(new MqConsumerIdempotencyHelper.ProcessingException());
        consume();verify(channel).basicNack(7,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
        verify(inbox,never()).recordFailure(anyString(),anyString(),any(),any(),anyInt());
    }
    @Test void firstFailureRequeuesUsingPersistentBudget() throws Exception {
        fails();consume();verify(inbox).recordFailure(eq("pay.queue"),eq("PAY"),eq(message),any(),eq(3));
        verify(channel).basicNack(7,false,true);
    }
    @Test void thirdFailureStillRequeues() throws Exception {
        fails();when(inbox.recordFailure(anyString(),anyString(),any(),any(),anyInt())).thenReturn(null);
        consume();verify(channel).basicNack(7,false,true);
    }
    @Test void exhaustedFailureNacksOnlyAfterDurableDlqHandoff() throws Exception {
        fails();when(inbox.recordFailure(anyString(),anyString(),any(),any(),anyInt())).thenReturn(MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED);
        consume();var sequence=inOrder(inbox,channel);sequence.verify(inbox).executeBatch(anyString(),anyString(),anyList(),any());
        sequence.verify(inbox).recordFailure(anyString(),anyString(),any(),any(),anyInt());sequence.verify(channel).basicNack(7,false,false);
    }
    @Test void databaseFailureNeverDiscardsMessage() throws Exception {
        fails();when(inbox.recordFailure(anyString(),anyString(),any(),any(),anyInt())).thenThrow(new IllegalStateException("DB down"));
        consume();verify(channel).basicNack(7,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void lateFailureDoesNotDowngradeConcurrentDone() throws Exception {
        fails();when(inbox.recordFailure(anyString(),anyString(),any(),any(),anyInt())).thenReturn(MqConsumerIdempotencyHelper.Outcome.DONE);
        consume();verify(channel).basicAck(7,false);verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void ackFailureDoesNotRecordBusinessFailureAndClosesChannelForRedelivery() throws Exception {
        succeeds();doThrow(new IOException("connection lost")).when(channel).basicAck(7,false);
        consume();verify(channel).abort();verify(inbox,never()).recordFailure(anyString(),anyString(),any(),any(),anyInt());
    }
    @Test void deadLetteredRedeliveryCannotRunBusinessOrResetRetryBudget() throws Exception {
        when(inbox.executeBatch(anyString(),anyString(),anyList(),any())).thenReturn(List.of(MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED));
        consume();verify(channel).basicNack(7,false,false);
        verify(inbox,never()).recordFailure(anyString(),anyString(),any(),any(),anyInt());
    }
    @Test void legacyBeginDelegatesButCannotUseRedisAsDone() {
        when(inbox.tryBeginConsume(any(),anyLong())).thenThrow(new IllegalStateException("Inbox required"));
        assertThrows(IllegalStateException.class,()->helper.tryBeginConsume(message,3600));
        helper.releaseConsume(message);verify(inbox).releaseConsume(message);
    }
    @Test void duplicateEventsInFailedBatchConsumeOneRetry() throws Exception {
        fails();
        var properties=new MessageProperties();properties.setMessageId("m-1");properties.setDeliveryTag(8);
        var duplicate=new Message(new byte[]{1},properties);
        when(inbox.resolveIdempotencyKey(message)).thenReturn("m-1");
        when(inbox.resolveIdempotencyKey(duplicate)).thenReturn("m-1");
        helper.consumeInboxBatch("pay.queue","PAY",List.of(new MqListenerHelper.Delivery(message,channel),
                new MqListenerHelper.Delivery(duplicate,channel)),indices->{});
        verify(inbox,times(1)).recordFailure(anyString(),anyString(),any(),any(),eq(3));
        verify(channel).basicNack(7,false,true);verify(channel).basicNack(8,false,true);
    }
    @Test void resolveKeyDelegatesAndClearingRetryDoesNotEraseInbox() {
        when(inbox.resolveIdempotencyKey(message)).thenReturn("m-1");assertEquals("m-1",helper.resolveIdempotencyKey(message));
        helper.clearConsumeRetry("pay.queue",message);verify(inbox,never()).releaseConsume(any());
    }
}
