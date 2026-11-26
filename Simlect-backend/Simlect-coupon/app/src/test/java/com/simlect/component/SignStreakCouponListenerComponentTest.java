package com.simlect.component;

import com.rabbitmq.client.Channel;
import com.simlect.api.dto.SignStreakCouponMessageDTO;
import com.simlect.biz.CouponInternalService;
import com.simlect.constants.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignStreakCouponListenerComponentTest {

    private boolean inboxDone;
    @org.junit.jupiter.api.BeforeEach
    void wireInboxRunnerMock() {
        // This fixture models a committed Inbox outcome. Common and container tests verify real transactions.
        org.mockito.Mockito.doAnswer(call -> {
            String queue = call.getArgument(0);
            Message message = call.getArgument(2);
            Channel channel = call.getArgument(3);
            Object payload = call.getArgument(4);
            if (inboxDone) { channel.basicAck(message.getMessageProperties().getDeliveryTag(), false); return null; }
            try {
                call.<Runnable>getArgument(5).run();
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            } catch (Exception failure) {
                mqListenerHelper.nackWithRetryOrDlq(channel,message.getMessageProperties().getDeliveryTag(),message,queue,payload,failure);
            }
            return null;
        }).when(mqListenerHelper).consumeInbox(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    private static final long DELIVERY_TAG = 73L;

    @Mock
    private CouponInternalService couponInternalService;
    @Mock
    private MqListenerHelper mqListenerHelper;
    @InjectMocks
    private SignStreakCouponListenerComponent listener;

    private SignStreakCouponMessageDTO payload() {
        return new SignStreakCouponMessageDTO("U1", "CP1", 7);
    }

    private Message rabbitMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(new byte[]{1}, properties);
    }

    @Test
    void handle_duplicateDelivery_acksWithoutGranting() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = rabbitMessage();
        inboxDone = true;

        listener.handle(payload(), channel, message);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(couponInternalService, never()).grantSignStreakCoupon(any());
    }

    @Test
    void handle_invalidPayload_clearsRetryAndAcks() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = rabbitMessage();
        SignStreakCouponMessageDTO invalid = new SignStreakCouponMessageDTO("U1", "", 7);
        inboxDone = false;

        listener.handle(invalid, channel, message);

        verify(mqListenerHelper).consumeInbox(eq(RabbitMQConfig.SIGN_STREAK_COUPON_QUEUE),org.mockito.ArgumentMatchers.anyString(),any(),any(),any(),any());
        verify(channel, never()).basicAck(anyLong(),anyBoolean());
        verify(mqListenerHelper).nackWithRetryOrDlq(any(),anyLong(),any(),any(),any(),any(Exception.class));
        verify(couponInternalService, never()).grantSignStreakCoupon(any());
    }

    @Test
    void handle_success_clearsRetryAndAcks() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = rabbitMessage();
        SignStreakCouponMessageDTO payload = payload();
        inboxDone = false;
        when(couponInternalService.grantSignStreakCoupon(payload))
                .thenReturn(new CouponInternalService.StreakCouponGrantResult("GRANTED", "UC1", null));

        listener.handle(payload, channel, message);

        verify(mqListenerHelper).consumeInbox(eq(RabbitMQConfig.SIGN_STREAK_COUPON_QUEUE),org.mockito.ArgumentMatchers.anyString(),any(),any(),any(),any());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void handle_systemFailure_nacksWithRetryOrDlq() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = rabbitMessage();
        SignStreakCouponMessageDTO payload = payload();
        IllegalStateException failure = new IllegalStateException("db down");
        inboxDone = false;
        when(couponInternalService.grantSignStreakCoupon(payload)).thenThrow(failure);

        listener.handle(payload, channel, message);

        verify(mqListenerHelper).nackWithRetryOrDlq(
                eq(channel), eq(DELIVERY_TAG), eq(message),
                eq(RabbitMQConfig.SIGN_STREAK_COUPON_QUEUE), eq(payload), eq(failure));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
