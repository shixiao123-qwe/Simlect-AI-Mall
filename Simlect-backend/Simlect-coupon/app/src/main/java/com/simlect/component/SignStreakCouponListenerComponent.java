package com.simlect.component;

import com.rabbitmq.client.Channel;
import com.simlect.api.dto.SignStreakCouponMessageDTO;
import com.simlect.biz.CouponInternalService;
import com.simlect.constants.RabbitMQConfig;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class SignStreakCouponListenerComponent {

    @Resource
    private CouponInternalService couponInternalService;
    @Resource
    private MqListenerHelper mqListenerHelper;

    @RabbitListener(queues = RabbitMQConfig.SIGN_STREAK_COUPON_QUEUE, ackMode = "MANUAL")
    public void handle(SignStreakCouponMessageDTO payload, Channel channel, Message mqMessage) throws IOException {
        mqListenerHelper.consumeInbox(RabbitMQConfig.SIGN_STREAK_COUPON_QUEUE,"SIGN_STREAK_COUPON",mqMessage,channel,payload,() -> {
            if (payload==null || StringTools.isEmpty(payload.getUserId()) || StringTools.isEmpty(payload.getCouponId())
                    || payload.getStreakDays()==null || payload.getStreakDays()<1)
                throw new MqConsumerIdempotencyHelper.InvalidEventException("连续签到奖励消息参数缺失");
            CouponInternalService.StreakCouponGrantResult result=couponInternalService.grantSignStreakCoupon(payload);
            log.info("连续签到奖励完成 userId={}, outcome={}",payload.getUserId(),result.outcome());
        });
    }
}
