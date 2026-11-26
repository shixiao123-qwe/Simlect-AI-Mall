package com.simlect.biz;

import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.po.OrderInfo;
import com.simlect.entity.po.OrderItem;
import com.simlect.entity.query.OrderInfoQuery;
import com.simlect.entity.query.OrderItemQuery;
import com.simlect.mappers.*;
import com.simlect.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AfterSaleService {
    public static final String POLICY_VERSION="demo-v3";
    private final OrderInfoMapper<OrderInfo,OrderInfoQuery> orders;
    private final OrderItemMapper<OrderItem,OrderItemQuery> items;
    private final AfterSaleMapper applications;
    @org.springframework.beans.factory.annotation.Autowired
    private AfterSalePolicyRules policyRules = new AfterSalePolicyRules();
    public AfterSaleService(OrderInfoMapper<OrderInfo,OrderInfoQuery> orders,
                            OrderItemMapper<OrderItem,OrderItemQuery> items, AfterSaleMapper applications) {
        this.orders=orders; this.items=items; this.applications=applications;
    }
    private OrderInfo owned(String userId, OrderItem item) {
        OrderInfo order=item==null ? null : orders.selectByOrderId(item.getOrderId());
        if (order==null || !Objects.equals(userId,order.getUserId()))
            throw new BusinessException("订单项不存在或无权访问");
        return order;
    }
    private Map<String,Object> decision(OrderInfo order, OrderItem item, String type) {
        if (!Set.of("refund","return_refund","exchange").contains(type))
            throw new BusinessException("不支持的售后类型");
        Integer st=order.getOrderStatus();
        boolean normal=Objects.equals(item.getOrderItemStatus(),1);
        boolean allowed=st!=null && (type.equals("refund") ? Set.of(1,7).contains(st) : Set.of(2,3,7).contains(st));
        boolean active=applications.active(item.getOrderItemId())!=null;
        boolean eligible=normal && allowed && !active;
        String reason=!normal ? "订单项已退款或状态异常" : !allowed ? "当前订单状态不支持此申请类型" : active ? "已有处理中售后申请" : "可提交人工审核；不代表批准或退款成功";
        // Current schema has no trustworthy signed-at or product-exception evidence.
        // Do not substitute order_time or free-text logistics records for receipt time.
        var window=policyRules.evaluate(type, AfterSalePolicyRules.Receipt.unavailable(),
                AfterSalePolicyRules.Applicability.UNKNOWN, java.time.Instant.now());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("eligible",eligible); result.put("reason",reason);
        result.put("orderId",order.getOrderId()); result.put("orderItemId",item.getOrderItemId());
        result.put("type",type); result.put("policyVersion",POLICY_VERSION);
        result.put("eligibilityScope","APPLICATION_ONLY");
        result.put("returnWindowStatus",window.status());
        result.put("returnWindowReason",window.reason());
        result.put("manualReviewRequired",true);
        result.put("freightStatus","UNCONFIGURED"); result.put("refundArrivalStatus","UNCONFIGURED");
        return result;
    }
    public Map<String,Object> check(String userId,String itemId,String type) {
        OrderItem item=items.selectByOrderItemId(itemId);
        return decision(owned(userId,item),item,type);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> create(String userId,AfterSaleDTO.Create dto) {
        // Check ownership BEFORE returning idempotent responses; serialize by order then item.
        OrderItem first=items.selectByOrderItemId(dto.orderItemId());
        OrderInfo firstOrder=owned(userId,first);
        OrderInfo order=applications.lockOrder(firstOrder.getOrderId());
        OrderItem item=applications.lockItem(dto.orderItemId());
        if (order==null || item==null || !Objects.equals(order.getUserId(),userId)
                || !Objects.equals(item.getOrderId(),order.getOrderId()))
            throw new BusinessException("订单项不存在或无权访问");
        Map<String,Object> previous=applications.byId(dto.requestId());
        if (previous!=null) {
            if (!Objects.equals(previous.get("userId"),userId)
                    || !Objects.equals(previous.get("orderItemId"),dto.orderItemId())
                    || !Objects.equals(previous.get("type"),dto.type())
                    || !Objects.equals(previous.get("reason"),dto.reason().trim()))
                throw new BusinessException("请求编号已使用，请重新发起申请");
            return previous;
        }
        Map<String,Object> eligibility=decision(order,item,dto.type());
        if (!Boolean.TRUE.equals(eligibility.get("eligible")))
            throw new BusinessException(eligibility.get("reason").toString());
        Map<String,Object> row=new HashMap<>();
        row.put("requestId",dto.requestId()); row.put("userId",userId);
        row.put("orderId",order.getOrderId()); row.put("orderItemId",dto.orderItemId());
        row.put("type",dto.type()); row.put("reason",dto.reason().trim());
        row.put("status","PENDING_REVIEW"); row.put("policyVersion",POLICY_VERSION);
        applications.insert(row); applications.insertLog(row);
        return row;
    }
    /** Query the original proposal under the same local locks used by create; never create here. */
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> result(String userId, AfterSaleDTO.Create dto) {
        OrderItem first=items.selectByOrderItemId(dto.orderItemId());
        OrderInfo firstOrder=owned(userId,first);
        OrderInfo order=applications.lockOrder(firstOrder.getOrderId());
        OrderItem item=applications.lockItem(dto.orderItemId());
        if (order==null || item==null || !Objects.equals(order.getUserId(),userId)
                || !Objects.equals(item.getOrderId(),order.getOrderId()))
            throw new BusinessException("订单项不存在或无权访问");
        Map<String,Object> row=applications.byId(dto.requestId());
        if (row!=null) {
            if (!Objects.equals(row.get("userId"),userId)
                    || !Objects.equals(row.get("orderItemId"),dto.orderItemId())
                    || !Objects.equals(row.get("type"),dto.type())
                    || !Objects.equals(row.get("reason"),dto.reason().trim()))
                throw new BusinessException("请求编号与原申请不一致");
            // SUCCEEDED means application submission succeeded, even if its review has progressed.
            return Map.of("state","SUCCEEDED","application",row,"retryAllowed",false);
        }
        boolean retryAllowed=Boolean.TRUE.equals(decision(order,item,dto.type()).get("eligible"));
        // Absence is a snapshot, not proof a delayed network request can never arrive.
        // Any retry MUST carry the same requestId and original payload through create's idempotency gate.
        return Map.of("state","NOT_FOUND","requestId",dto.requestId(),"retryAllowed",retryAllowed);
    }
    public List<Map<String,Object>> mine(String userId) { return applications.mine(userId); }
}
