package com.simlect.controller;

import com.simlect.api.support.OrderFeignSupport;
import com.simlect.component.RedisComponent;
import com.simlect.component.SpringContext;
import com.simlect.constants.Constants;
import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.api.enums.PayChannelEnum;
import com.simlect.exception.BusinessException;
import com.simlect.biz.PayChannel;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/notify")
/**
 * 【功能】支付渠道异步回调入口（当前主路径：支付宝 notify）。
 * <p>
 * 【角色】pay 服务对外回调 Controller；验签与渠道解析在本服务，订单状态变更 Feign 回 order 服务。
 * <p>
 * 【调用链】支付宝 POST → alipayNotify → PayChannel.payNotify 验签
 * → Redis 回调幂等锁 → orderFeignSupport.paySuccess → OrderInfoServiceImpl.paySuccess。
 * 必须尽快返回 success/failure，业务幂等靠锁 + 订单状态机。
 */
public class PayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PayNotifyController.class);

    @Resource
    private OrderFeignSupport orderFeignSupport;
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private RedissonClient redissonClient;

    /** 回调互斥锁等待时间（毫秒） */
    private static final long PAY_NOTIFY_LOCK_WAIT_MS = 3_000L;

    @PostMapping("/alipayNotify")
    public String alipayNotify(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        for (var entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            // Reject parameter pollution rather than verifying one value and processing another.
            if (values == null || values.length != 1 || values[0] == null) return "failure";
            params.put(entry.getKey(), values[0]);
        }
        PayChannel payChannel = (PayChannel) SpringContext.getBean(PayChannelEnum.ALIPAY_PC.getBeanName());
        PayOrderNotifyDTO notifyDTO;
        try {
            notifyDTO = payChannel.payNotify(params, null);
        } catch (Exception e) {
            log.error("支付宝回调验签失败", e);
            return "failure";
        }
        if (notifyDTO == null) {
            return "success";
        }
        if (notifyDTO.getPayOrderId() == null || notifyDTO.getPayOrderId().isBlank()
                || notifyDTO.getChannelOrderId() == null || notifyDTO.getChannelOrderId().isBlank()
                || notifyDTO.getTotalAmount() == null || notifyDTO.getTotalAmount().signum() <= 0
                || notifyDTO.getTotalAmount().scale() > 2) return "failure";
        String payOrderId = notifyDTO.getPayOrderId();
        // Redisson 锁（看门狗续期），避免重复回调并发处理；释放由 Redisson 校验持有者
        String lockKey = Constants.REDIS_KEY_PAY_NOTIFY_LOCK + payOrderId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(PAY_NOTIFY_LOCK_WAIT_MS, -1, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.info("支付宝回调处理中，等待渠道重试 payOrderId={}", payOrderId);
                // A busy lock does not prove the holder will commit. Let Alipay retry.
                return "failure";
            }
            orderFeignSupport.paySuccess(notifyDTO);
        } catch (BusinessException e) {
            log.warn("支付宝回调业务处理失败 payOrderId={}, msg={}", payOrderId, e.getMessage());
            return "failure";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("支付宝回调处理被中断 payOrderId={}", payOrderId, e);
            return "failure";
        } catch (Exception e) {
            log.error("支付宝回调处理异常 payOrderId={}", payOrderId, e);
            return "failure";
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return "success";
    }
}
