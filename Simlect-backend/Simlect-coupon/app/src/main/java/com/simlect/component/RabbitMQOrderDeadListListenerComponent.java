package com.simlect.component;

import com.simlect.api.support.OrderFeignSupport;
import com.simlect.api.vo.OrderBriefVO;
import com.simlect.constants.RabbitMQConfig;
import com.simlect.api.dto.RushingCouponMessageDTO;
import com.simlect.api.enums.OrderStatusEnum;
import com.simlect.api.enums.UserCouponStatusEnum;
import com.simlect.entity.po.UserCoupon;
import com.simlect.biz.DiscountCouponService;
import com.simlect.biz.UserCouponService;
import com.simlect.utils.StringTools;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class RabbitMQOrderDeadListListenerComponent {

    @Resource
    private DiscountCouponService discountCouponService;
    @Resource
    private UserCouponService userCouponService;
    @Resource
    private OrderFeignSupport orderFeignSupport;
    @Resource
    private MqListenerHelper mqListenerHelper;

    @RabbitListener(queues = RabbitMQConfig.RUSHING_DEAD_QUEUE, ackMode = "MANUAL")
    public void handleDeadOrder(RushingCouponMessageDTO message, Channel channel, Message mqMessage) throws IOException {
        mqListenerHelper.consumeInbox(RabbitMQConfig.RUSHING_DEAD_QUEUE,"COUPON_RESERVATION_TIMEOUT",mqMessage,channel,message,() -> {
            if (message==null) throw new MqConsumerIdempotencyHelper.InvalidEventException("抢购消息缺失");
            String userCouponId = message.getUserCouponId();
            String userId = message.getUserId();
            String couponId = message.getCouponId();

            log.info("抢购死信兜底: userCouponId={}", userCouponId);

            UserCoupon userCoupon = userCouponService.getUserCouponByUserCouponId(userCouponId);
            OrderBriefVO orderInfo = StringTools.isEmpty(message.getOrderId())
                    ? null
                    : orderFeignSupport.getOrder(message.getOrderId());

            if (StringTools.isEmpty(message.getOrderId())) {
                if (userCoupon == null) {
                    log.warn("抢购预占超时未建单，回补库存: couponId={}, userId={}", couponId, userId);
                    discountCouponService.releaseRushCouponReserve(couponId, userId);
                }
                return;
            }

            if (userCoupon == null && orderInfo == null) {
                log.warn("建单未落库，仅回滚 Redis 预占: {}", userCouponId);
                discountCouponService.releaseRushRedisReserve(couponId, userId);
                return;
            }

            if (orderInfo != null
                    && OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(orderInfo.getOrderStatus())) {
                orderFeignSupport.cancelOrder(message.getOrderId(), null);
            } else if (userCoupon != null
                    && UserCouponStatusEnum.CANT.getStatus().equals(userCoupon.getStatus())
                    && orderInfo == null) {
                log.warn("用户券存在但订单缺失，仅回滚 Redis: {}", userCouponId);
                discountCouponService.releaseRushRedisReserve(couponId, userId);
            }

        });
    }
}
