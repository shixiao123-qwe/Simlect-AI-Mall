package com.simlect.inbox;

import com.simlect.component.*;
import com.simlect.constants.TransactionalMqSender;
import com.simlect.mappers.MqInboxMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Transaction model tests. Real MySQL rollback/locking are covered by InboxConsistencyIT. */
class InboxTransactionTest {
    Map<String,Map<String,Object>> rows;
    int effects;
    MqInboxMapper mapper;
    TransactionalMqSender outbox;
    MqConsumerIdempotencyHelper inbox;
    static class Exit extends Error { }
    static class Tx { Map<String,Map<String,Object>> snapshot; int effects; }
    @BeforeEach void setup() {
        rows=new HashMap<>();mapper=mock(MqInboxMapper.class);outbox=mock(TransactionalMqSender.class);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Tx(); }
            protected void doBegin(Object object,TransactionDefinition definition) {
                Tx tx=(Tx)object;tx.snapshot=new HashMap<>();rows.forEach((key,value)->tx.snapshot.put(key,new HashMap<>(value)));tx.effects=effects;
            }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) {
                Tx tx=(Tx)status.getTransaction();rows.clear();rows.putAll(tx.snapshot);effects=tx.effects;
            }
        };
        inbox=new MqConsumerIdempotencyHelper();ReflectionTestUtils.setField(inbox,"inboxMapper",mapper);
        ReflectionTestUtils.setField(inbox,"transactionManager",manager);ReflectionTestUtils.setField(inbox,"transactionalMqSender",outbox);
        when(mapper.insertProcessing(anyString(),anyString(),anyString())).thenAnswer(call->insert(call.getArgument(0),call.getArgument(1),"PROCESSING"));
        when(mapper.insertFailed(anyString(),anyString(),anyString())).thenAnswer(call->insert(call.getArgument(0),call.getArgument(1),"FAILED"));
        when(mapper.lock(anyString(),anyString())).thenAnswer(call->new HashMap<>(rows.get(key(call.getArgument(0),call.getArgument(1)))));
        when(mapper.begin(anyString(),anyString())).thenAnswer(call->{var row=row(call.getArgument(0),call.getArgument(1));if(!"FAILED".equals(row.get("status")))return 0;row.put("status","PROCESSING");return 1;});
        when(mapper.complete(anyString(),anyString())).thenAnswer(call->{var row=row(call.getArgument(0),call.getArgument(1));row.put("status","DONE");return 1;});
        when(mapper.fail(anyString(),anyString(),anyString())).thenAnswer(call->{var row=row(call.getArgument(0),call.getArgument(1));row.put("failureCount",(Integer)row.get("failureCount")+1);row.put("error",call.getArgument(2));return 1;});
        when(mapper.markDeadLettered(anyString(),anyString())).thenAnswer(call->{row(call.getArgument(0),call.getArgument(1)).put("dlqEnqueued",1);return 1;});
    }
    String key(String consumer,String id) { return consumer+":"+id; }
    Map<String,Object> row(String consumer,String id) { return rows.get(key(consumer,id)); }
    int insert(String consumer,String id,String status) {
        if(rows.containsKey(key(consumer,id)))return 0;
        rows.put(key(consumer,id),new HashMap<>(Map.of("status",status,"failureCount",0,"dlqEnqueued",0)));return 1;
    }
    Message message(String id) { var props=new MessageProperties();props.setMessageId(id);props.setDeliveryTag(7);return new Message(new byte[]{1},props); }
    void execute() { inbox.executeBatch("consumer","ORDER",List.of(message("event")),indices->effects++); }
    @Test void interruptedBeforeConsumptionDoesNotCreateCompletion() { assertTrue(rows.isEmpty());assertEquals(0,effects); }
    @Test void midTransactionExitRollsBackBothBusinessAndInbox() {
        assertThrows(Exit.class,()->inbox.executeBatch("consumer","ORDER",List.of(message("event")),indices->{effects++;throw new Exit();}));
        assertEquals(0,effects);assertTrue(rows.isEmpty());execute();assertEquals(1,effects);
    }
    @Test void redeliveryAfterCommitDoesNotRepeatBusiness() { execute();execute();assertEquals(1,effects);assertEquals("DONE",row("consumer","event").get("status")); }
    @Test void differentConsumersHaveIndependentCompletion() {
        execute();inbox.executeBatch("another","ORDER",List.of(message("event")),indices->effects++);assertEquals(2,effects);assertEquals(2,rows.size());
    }
    @Test void failedRecordCanRetryInSameBusinessTransaction() { insert("consumer","event","FAILED");execute();assertEquals(1,effects);assertEquals("DONE",row("consumer","event").get("status")); }
    @Test void processingRecordIsNotCompletedOrAcked() {
        insert("consumer","event","PROCESSING");assertThrows(MqConsumerIdempotencyHelper.ProcessingException.class,this::execute);assertEquals(0,effects);
    }
    @Test void duplicateWithinBatchRunsOneBusinessItem() {
        inbox.executeBatch("consumer","ORDER",List.of(message("event"),message("event")),indices->{assertEquals(1,indices.size());effects+=indices.size();});assertEquals(1,effects);
    }
    @Test void entireBatchRollsBackWhenBusinessFails() {
        assertThrows(IllegalStateException.class,()->inbox.executeBatch("consumer","ORDER",List.of(message("a"),message("z")),indices->{effects+=2;throw new IllegalStateException("batch insert failed");}));
        assertTrue(rows.isEmpty());assertEquals(0,effects);
    }
    @Test void exhaustedFailurePersistsAndEnqueuesOneDurableDlqCopy() {
        var failure=new IllegalStateException("db business failed");
        for(int i=1;i<=3;i++)assertNull(inbox.recordFailure("consumer","ORDER",message("event"),failure,3));
        assertEquals(MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED,inbox.recordFailure("consumer","ORDER",message("event"),failure,3));
        assertEquals(MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED,inbox.recordFailure("consumer","ORDER",message("event"),failure,3));
        assertEquals(4,row("consumer","event").get("failureCount"));
        verify(outbox,times(1)).sendAfterCommit(anyString(),anyString(),any(),startsWith("inbox-dlq:"),any());
    }
    @Test void failureAfterConcurrentDoneDoesNotDowngradeCompletion() {
        execute();assertEquals(MqConsumerIdempotencyHelper.Outcome.DONE,inbox.recordFailure("consumer","ORDER",message("event"),new IllegalStateException("late failure"),3));
        assertEquals(0,row("consumer","event").get("failureCount"));verifyNoInteractions(outbox);
    }
    @Test void dlqOutboxFailureRollsBackFailureHandoff() {
        for(int i=0;i<3;i++)inbox.recordFailure("consumer","ORDER",message("event"),new IllegalStateException("fail"),3);
        doThrow(new IllegalStateException("Outbox table unavailable")).when(outbox).sendAfterCommit(anyString(),anyString(),any(),anyString(),any());
        assertThrows(IllegalStateException.class,()->inbox.recordFailure("consumer","ORDER",message("event"),new IllegalStateException("fail"),3));
        assertEquals(3,row("consumer","event").get("failureCount"));assertEquals(0,row("consumer","event").get("dlqEnqueued"));
    }
    @Test void missingEventIdCannotRunBusinessAndIsQuarantined() {
        assertThrows(MqConsumerIdempotencyHelper.InvalidEventException.class,()->inbox.executeBatch("consumer","ORDER",List.of(message(null)),indices->effects++));
        assertEquals(0,effects);assertEquals(MqConsumerIdempotencyHelper.Outcome.DEAD_LETTERED,
                inbox.recordFailure("consumer","ORDER",message(null),new MqConsumerIdempotencyHelper.InvalidEventException("missing ID"),3));
    }
    @Test void ackFailurePreservesDoneAndRetryStillSkipsBusiness() throws Exception {
        var listener=new MqListenerHelper();ReflectionTestUtils.setField(listener,"mqConsumerIdempotencyHelper",inbox);
        var channel=mock(Channel.class);doThrow(new IOException("ACK connection lost")).when(channel).basicAck(7,false);
        listener.consumeInbox("consumer","ORDER",message("event"),channel,null,()->effects++);
        verify(channel).abort();execute();assertEquals(1,effects);assertEquals("DONE",row("consumer","event").get("status"));
    }
}
