package com.simlect.biz;

import com.simlect.component.RedisComponent;
import com.simlect.api.support.PayFeignSupport;
import com.simlect.entity.query.OrderInfoQuery;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.po.*;
import com.simlect.mappers.*;
import com.simlect.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class AfterSaleWorkflowService {
    private final AfterSaleWorkflowMapper mapper;
    private final AfterSaleMapper application;
    private final OrderInfoService orders;
    private final OrderItemService items;
    private final RedisComponent redis;
    private final PayFeignSupport pay;
    private final TransactionTemplate tx;
    @Value("${simlect.after-sale.return-address:}") private String returnAddress;
    @Value("${simlect.after-sale.refund-enabled:false}") private boolean refundEnabled;
    @Value("${simlect.pay.local-mock-enabled:false}") private boolean mockEnabled;
    public AfterSaleWorkflowService(AfterSaleWorkflowMapper mapper,AfterSaleMapper application,
            OrderInfoService orders,OrderItemService items,RedisComponent redis,PayFeignSupport pay,PlatformTransactionManager manager) {
        this.mapper=mapper;this.application=application;this.orders=orders;this.items=items;this.redis=redis;this.pay=pay;
        this.tx=new TransactionTemplate(manager);
    }
    public boolean simulated() { return mockEnabled; }
    private Map<String,Object> required(String id) {
        Map<String,Object> row=mapper.detail(id);
        if (row==null) throw new BusinessException("售后申请不存在或无权访问");
        return row;
    }
    private Map<String,Object> locked(String id) {
        if (mapper.lockRequest(id)==null) throw new BusinessException("售后申请不存在或无权访问");
        mapper.ensure(id);return required(id);
    }
    private Map<String,Object> owned(String user,String id) {
        Map<String,Object> row=required(id);
        if (!Objects.equals(row.get("userId"),user)) throw new BusinessException("售后申请不存在或无权访问");
        return row;
    }
    public List<Map<String,Object>> mine(String user) { return mapper.mine(user); }
    public List<Map<String,Object>> list(String status,int page) {
        if (status!=null && !Set.of("PENDING_REVIEW","APPROVED","REJECTED","COMPLETED").contains(status)) throw new BusinessException("无效售后状态");
        if (page<1 || page>200) throw new BusinessException("页码超出范围");
        return mapper.list(status,(page-1)*50);
    }
    public Map<String,Object> detail(String id) {
        Map<String,Object> row=new LinkedHashMap<>(required(id));row.put("logs",mapper.logs(id));return row;
    }
    public Map<String,Object> detailOwned(String user,String id) { owned(user,id);return detail(id); }
    private void move(Map<String,Object> row,String status,String phase,String actor,String note) {
        String id=(String)row.get("requestId");String from=(String)row.get("phase");
        if (mapper.status(id,(String)row.get("status"),status)!=1 || mapper.phase(id,from,phase)!=1)
            throw new BusinessException("售后状态已变化，请刷新重试");
        mapper.log(id,from,phase,actor,note);
    }
    private OrderInfo order(Map<String,Object> row) {
        OrderInfo o=orders.getOrderInfoByOrderId((String)row.get("orderId"));
        if (o==null || !Objects.equals(o.getUserId(),row.get("userId"))) throw new BusinessException("订单归属异常");
        return o;
    }
    public static boolean allowsQuickRefund(Integer orderStatus, Integer logisticsStatus) {
        return (Objects.equals(orderStatus, 1) || Objects.equals(orderStatus, 7))
                && Objects.equals(logisticsStatus, 0);
    }
    private boolean notShipped(OrderInfo order) {
        return allowsQuickRefund(order.getOrderStatus(), mapper.logisticsStatus(order.getOrderId()));
    }
    private boolean canReturn(OrderInfo order) {
        if (Objects.equals(order.getOrderStatus(),2) || Objects.equals(order.getOrderStatus(),3)) return true;
        Integer logistics=mapper.logisticsStatus(order.getOrderId());
        return Objects.equals(order.getOrderStatus(),7) && (Objects.equals(logistics,1) || Objects.equals(logistics,2));
    }
    public Map<String,Object> review(String actor,AfterSaleDTO.Review dto) {
        return tx.execute(s->{
            Map<String,Object> row=locked(dto.requestId());String status=(String)row.get("status");
            if (Set.of("REJECTED","COMPLETED").contains(status)) throw new BusinessException("申请已处理，不能再次审核");
            if (!dto.approved()) {
                if (row.get("refundReference")!=null || Set.of("REFUNDING","REFUND_FAILED").contains(row.get("phase")))
                    throw new BusinessException("退款已开始或结果待核实，不能驳回释放申请；请核实后重试原退款单");
                mapper.review(dto.requestId(),dto.note().trim(),(String)row.get("returnAddress"));
                move(row,"REJECTED","REJECTED",actor,dto.note().trim());return detail(dto.requestId());
            }
            if (!"PENDING_REVIEW".equals(status)) throw new BusinessException("申请已经审核，请刷新");
            if ("exchange".equals(row.get("type"))) throw new BusinessException("换货出库尚未接入，不能批准换货；请人工协商后驳回或重新申请退货退款");
            OrderInfo o=order(row);OrderItem item=items.getOrderItemByOrderItemId((String)row.get("orderItemId"));
            if (item==null || !Objects.equals(item.getOrderItemStatus(),1)) throw new BusinessException("订单项已退款或状态异常");
            boolean returning="return_refund".equals(row.get("type"));
            if (returning ? !canReturn(o) : !notShipped(o)) throw new BusinessException("当前订单或物流状态不支持此售后类型");
            if (returning && (returnAddress==null || returnAddress.isBlank())) throw new BusinessException("请先配置真实退货地址；本地测试请使用明确标记的演示地址");
            mapper.review(dto.requestId(),dto.note().trim(),returning?returnAddress:null);
            move(row,"APPROVED",returning?"WAIT_RETURN":"READY_REFUND",actor,dto.note().trim());
            return detail(dto.requestId());
        });
    }
    public Map<String,Object> submitReturn(String user,AfterSaleDTO.ReturnParcel dto) {
        return tx.execute(s->{
            Map<String,Object> row=locked(dto.requestId());
            if (!Objects.equals(row.get("userId"),user)) throw new BusinessException("售后申请不存在或无权访问");
            if (!"APPROVED".equals(row.get("status")) || !"WAIT_RETURN".equals(row.get("phase")) || !"return_refund".equals(row.get("type")))
                throw new BusinessException("当前申请不能填写退货单号");
            mapper.tracking(dto.requestId(),dto.carrier().trim(),dto.tracking());
            move(row,"APPROVED","WAIT_INSPECTION",user,"用户提交退货单号，待仓库核验");return detail(dto.requestId());
        });
    }
    public Map<String,Object> inspect(String actor,AfterSaleDTO.Inspect dto) {
        return tx.execute(s->{
            Map<String,Object> row=locked(dto.requestId());
            if (!"APPROVED".equals(row.get("status")) || !"WAIT_INSPECTION".equals(row.get("phase")) || !"return_refund".equals(row.get("type")))
                throw new BusinessException("仅已提交退货单号的退货退款申请可验货");
            if (!dto.accepted() && dto.restockable()) throw new BusinessException("验货拒绝不能标记为可入库");
            mapper.inspection(dto.requestId(),dto.note().trim(),dto.accepted() && dto.restockable());
            move(row,dto.accepted()?"APPROVED":"REJECTED",dto.accepted()?"READY_REFUND":"REJECTED",actor,dto.note().trim());
            return detail(dto.requestId());
        });
    }
    public Map<String,Object> refund(String actor,String id) {
        if (!refundEnabled) throw new BusinessException("售后退款执行未开启");
        Map<String,Object> initial=required(id);OrderInfo initialOrder=order(initial);
        String payId=initialOrder.getPayOrderId();
        if (payId==null || payId.isBlank()) throw new BusinessException("支付单号缺失，不能退款");
        return redis.runWithPayOrderLifecycleLock(payId,()->{
            try {
                // Persist the original refund key BEFORE any external effect, so Redis expiry
                // or a DB rollback cannot cause a new provider refund number on retry.
                tx.execute(s->{
                    Map<String,Object> row=locked(id);
                    if ("COMPLETED".equals(row.get("status"))) return null;
                    if (!"APPROVED".equals(row.get("status")) || !Set.of("READY_REFUND","REFUND_FAILED").contains(row.get("phase")))
                        throw new BusinessException("申请未审核或未验货通过，不能退款");
                    String itemId=(String)row.get("orderItemId");
                    String cached=redis.getRefundOrderIdAnchor(itemId);
                    String persisted=(String)row.get("refundReference");
                    OrderItem existing=items.getOrderItemByOrderItemId(itemId);
                    String completedReference=existing!=null && Objects.equals(existing.getOrderItemStatus(),0)?existing.getRefundOrderId():null;
                    String reference=persisted!=null?persisted:(cached!=null?cached:(completedReference!=null?completedReference:id));
                    if (cached!=null && !cached.equals(reference)) throw new BusinessException("退款单号冲突，请人工核实");
                    if (mapper.bindReference(id,reference)!=1) throw new BusinessException("退款单号绑定失败");
                    redis.bindRefundOrderIdAnchor(itemId,reference);
                    return null;
                });
                return tx.execute(s->{
                    application.lockOrder((String)initial.get("orderId"));
                    application.lockItem((String)initial.get("orderItemId"));
                    Map<String,Object> row=locked(id);
                    if ("COMPLETED".equals(row.get("status"))) return detail(id);
                    if (!"APPROVED".equals(row.get("status")) || !Set.of("READY_REFUND","REFUND_FAILED").contains(row.get("phase")))
                        throw new BusinessException("申请未审核或未验货通过，不能退款");
                    OrderInfo o=order(row);
                    boolean demo=o.getChannelOrderId()!=null && o.getChannelOrderId().startsWith("LOCAL_DEMO_");
                    if (demo!=mockEnabled) throw new BusinessException("订单资金模式与支付配置不一致，禁止退款");
                    OrderItem item=items.getOrderItemByOrderItemId((String)row.get("orderItemId"));
                    if (item==null) throw new BusinessException("订单项不存在");
                    boolean returning="return_refund".equals(row.get("type"));
                    if (!returning && !"refund".equals(row.get("type"))) throw new BusinessException("此类型尚未支持退款履约");
                    if (Objects.equals(item.getOrderItemStatus(),1)) {
                        if (!returning && !notShipped(o)) throw new BusinessException("已发货订单请先走退货验货流程");
                        move(row,"APPROVED","REFUNDING",actor,"管理员确认执行退款");
                        boolean restock=!returning || (row.get("restockable") instanceof Number n && n.intValue()==1) || Boolean.TRUE.equals(row.get("restockable"));
                        orders.refundAfterSale(item,(String)row.get("userId"),restock);
                        row=required(id);
                    }
                    OrderItem actual=items.getOrderItemByOrderItemId((String)row.get("orderItemId"));
                    if (actual==null || !Objects.equals(actual.getOrderItemStatus(),0) || actual.getRefundAmount()==null || actual.getRefundAmount().signum()<=0 || actual.getRefundOrderId()==null || !Objects.equals(actual.getRefundOrderId(),row.get("refundReference")))
                        throw new BusinessException("退款结果待核实，不能标记售后完成");
                    // A combined payment is fully refunded only when ALL child orders are refunded.
                    OrderInfoQuery payQuery=new OrderInfoQuery();payQuery.setPayOrderId(payId);
                    List<OrderInfo> siblings=orders.findListByParam(payQuery);
                    if (siblings==null || siblings.isEmpty()) throw new BusinessException("支付关联订单待核实，不能标记完成");
                    if (siblings.stream().allMatch(child->Objects.equals(child.getOrderStatus(),6)))
                        pay.markRefunded(payId);
                    mapper.result(id,actual.getRefundAmount(),actual.getRefundOrderId(),mockEnabled);
                    move(row,"COMPLETED","COMPLETED",actor,mockEnabled?"本地模拟退款完成，未发生真实资金退回":"支付渠道退款已受理；库存回补失败将由原补偿任务处理");
                    return detail(id);
                });
            } catch (RuntimeException failure) {
                tx.execute(s->{
                    Map<String,Object> row=locked(id);
                    if ("APPROVED".equals(row.get("status")) && Set.of("READY_REFUND","REFUND_FAILED").contains(row.get("phase")))
                        move(row,"APPROVED","REFUND_FAILED",actor,"退款未完成，请核实渠道及订单后使用原退款单号重试");
                    return null;
                });
                throw failure;
            }
        });
    }
}
