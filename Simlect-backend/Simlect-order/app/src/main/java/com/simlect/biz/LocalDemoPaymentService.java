package com.simlect.biz;

import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.component.RedisComponent;
import com.simlect.entity.config.AppConfig;
import com.simlect.entity.po.OrderInfo;
import com.simlect.entity.query.OrderInfoQuery;
import com.simlect.exception.BusinessException;
import com.simlect.utils.OrderPayAmountUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Explicit local-only simulation, reusing the real order lifecycle and payment record path. */
@Service
public class LocalDemoPaymentService {
    private final OrderInfoService orders;
    private final RedisComponent redis;
    private final AppConfig config;
    @Value("${simlect.pay.local-mock-enabled:false}") private boolean mockEnabled;
    @Value("${simlect.pay.demo-confirm-enabled:false}") private boolean confirmEnabled;
    @Value("${simlect.production-ready:false}") private boolean productionReady;
    public LocalDemoPaymentService(OrderInfoService orders,RedisComponent redis,AppConfig config) {
        this.orders=orders;this.redis=redis;this.config=config;
    }
    @PostConstruct public void validateConfiguration() {
        if (confirmEnabled && (productionReady || !mockEnabled))
            throw new IllegalStateException("模拟付款只允许在非生产且支付占位模式下启用");
    }
    private void requireEnabled() {
        if (!confirmEnabled || !mockEnabled || productionReady)
            throw new BusinessException("本地模拟付款未启用");
    }
    public Map<String,Object> confirm(String userId,String payOrderId) {
        requireEnabled();
        if (userId==null || userId.isBlank() || payOrderId==null || !payOrderId.matches("[A-Za-z0-9]{1,32}"))
            throw new BusinessException("支付订单号无效");
        // Reentrant lifecycle lock: the existing paySuccess uses the same Redisson RLock.
        return redis.runWithPayOrderLifecycleLock(payOrderId,()->confirmInLock(userId,payOrderId));
    }
    private Map<String,Object> confirmInLock(String userId,String payOrderId) {
        requireEnabled();
        OrderInfoQuery query=new OrderInfoQuery();query.setPayOrderId(payOrderId);
        List<OrderInfo> rows=orders.findListByParam(query);
        if (rows==null || rows.isEmpty() || rows.stream().anyMatch(o->o==null || !userId.equals(o.getUserId())))
            throw new BusinessException("订单不存在或无权访问");
        if (redis.isPayTradeInitiated(payOrderId))
            throw new BusinessException("该订单已发起真实支付，不能模拟付款");
        String channelId="LOCAL_DEMO_"+payOrderId;
        if (rows.stream().allMatch(o->o.getOrderStatus()!=null && Set.of(1,2,3).contains(o.getOrderStatus()))) {
            if (rows.stream().anyMatch(o->!channelId.equals(o.getChannelOrderId())))
                throw new BusinessException("该订单不是本地模拟付款订单");
            return Map.of("payOrderId",payOrderId,"simulated",true,"alreadyPaid",true);
        }
        if (rows.stream().anyMatch(o->!Objects.equals(0,o.getOrderStatus())) || redis.isPayOrderCloseMarked(payOrderId))
            throw new BusinessException("订单已关闭或状态不支持付款，请重新下单");
        int minutes=config.getOrderExpireMinute()==null ? 15 : config.getOrderExpireMinute();
        Instant cutoff=Instant.now().minusSeconds(minutes*60L);
        if (rows.stream().anyMatch(o->o.getOrderTime()==null || !o.getOrderTime().toInstant().isAfter(cutoff)))
            throw new BusinessException("订单已超时，请重新下单");
        if (rows.stream().anyMatch(o->o.getAmount()==null || o.getAmount().signum()<0))
            throw new BusinessException("订单金额异常");
        BigDecimal total=rows.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        PayOrderNotifyDTO notification=new PayOrderNotifyDTO(payOrderId,channelId);
        notification.setTotalAmount(OrderPayAmountUtil.normalizeChannelPayAmount(total));
        orders.paySuccess(notification);
        // Read actual post-state; never report success on a rejected callback.
        List<OrderInfo> after=orders.findListByParam(query);
        if (after==null || after.size()!=rows.size() || after.stream().anyMatch(o->
                !Objects.equals(1,o.getOrderStatus()) || !channelId.equals(o.getChannelOrderId())))
            throw new BusinessException("模拟付款结果待核实，请查看订单状态后再试");
        return Map.of("payOrderId",payOrderId,"simulated",true,"alreadyPaid",false);
    }
}
