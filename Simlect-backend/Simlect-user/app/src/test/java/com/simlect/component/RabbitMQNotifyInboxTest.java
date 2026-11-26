package com.simlect.component;

import com.rabbitmq.client.Channel;
import com.simlect.api.dto.NotificationMessageDTO;
import com.simlect.biz.UserNotificationService;
import com.simlect.entity.po.UserNotification;
import com.simlect.redis.RedisUtils;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RabbitMQNotifyInboxTest {
    RabbitMQNotifyListenerComponent listener;
    MqListenerHelper inbox;
    UserNotificationService service;
    NotifyPushPublisher push;
    RedisUtils redis;
    Channel channel;
    @BeforeEach void setup() {
        listener=new RabbitMQNotifyListenerComponent();inbox=mock(MqListenerHelper.class);
        service=mock(UserNotificationService.class);push=mock(NotifyPushPublisher.class);
        redis=mock(RedisUtils.class);channel=mock(Channel.class);
        ReflectionTestUtils.setField(listener,"mqListenerHelper",inbox);
        ReflectionTestUtils.setField(listener,"userNotificationService",service);
        ReflectionTestUtils.setField(listener,"notifyPushPublisher",push);
        ReflectionTestUtils.setField(listener,"redisUtils",redis);
    }
    @AfterEach void cleanup() {
        ((ScheduledExecutorService)ReflectionTestUtils.getField(listener,"scheduler")).shutdownNow();
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
    }
    void add(String id,String title) {
        var properties=new MessageProperties();properties.setMessageId(id);properties.setDeliveryTag(1);
        listener.handleNotify(new NotificationMessageDTO("u1",title,"content","logistics","order1"),
                channel,new Message(new byte[]{1},properties));
    }
    void flush() {ReflectionTestUtils.invokeMethod(listener,"flushRemaining");}
    @Test void bufferedMessageIsNotAcknowledgedOrInsertedBeforeInboxFlush() {
        add("event1","first");verifyNoInteractions(channel,service,inbox,push);
        flush();verify(inbox).consumeInboxBatch(anyString(),eq("USER_NOTIFICATION"),argThat(list->list.size()==1),any());
        verifyNoInteractions(channel,service,push);
    }
    @Test void onlyNewlyClaimedBatchRowsAreInsertedAndPushWaitsForCommit() {
        add("done-event","already done");add("new-event","new notification");
        doAnswer(call->{
            Consumer<List<Integer>> business=call.getArgument(3);
            TransactionSynchronizationManager.initSynchronization();
            business.accept(List.of(1));
            verify(service).batchInsert(argThat(rows->rows.size()==1 && "new notification".equals(rows.get(0).getTitle())));
            verifyNoInteractions(push,redis,channel);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCommit());
            TransactionSynchronizationManager.clearSynchronization();return null;
        }).when(inbox).consumeInboxBatch(anyString(),anyString(),anyList(),any());
        flush();verify(push,times(1)).push(any(UserNotification.class));
    }
    @Test void failedBatchInsertCannotPushOrAckInListener() {
        add("event1","first");doThrow(new IllegalStateException("database failure")).when(service).batchInsert(anyList());
        doAnswer(call->{Consumer<List<Integer>> business=call.getArgument(3);business.accept(List.of(0));return null;})
                .when(inbox).consumeInboxBatch(anyString(),anyString(),anyList(),any());
        assertThrows(IllegalStateException.class,this::flush);verifyNoInteractions(push,redis,channel);
    }
}
