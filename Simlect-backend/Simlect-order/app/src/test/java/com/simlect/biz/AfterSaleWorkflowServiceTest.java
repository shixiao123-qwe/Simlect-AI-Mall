package com.simlect.biz;
import com.simlect.component.RedisComponent;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.po.*;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AfterSaleWorkflowServiceTest {
    AfterSaleWorkflowMapper mapper;AfterSaleMapper application;OrderInfoService orders;OrderItemService items;RedisComponent redis;
    com.simlect.api.support.PayFeignSupport pay;
    AfterSaleWorkflowService service;Map<String,Object> row;OrderInfo order;OrderItem item;
    String id="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @BeforeEach void setup() throws Exception {
        row=new HashMap<>(Map.of("requestId",id,"userId","u1","orderId","o1","orderItemId","o1_1","type","refund","status","PENDING_REVIEW","phase","PENDING_REVIEW"));
        mapper=mock(AfterSaleWorkflowMapper.class);application=mock(AfterSaleMapper.class);orders=mock(OrderInfoService.class);items=mock(OrderItemService.class);redis=mock(RedisComponent.class);
        var manager=new AbstractPlatformTransactionManager(){
            protected Object doGetTransaction(){return new HashMap<String,Object>();}
            @SuppressWarnings("unchecked") protected void doBegin(Object tx,TransactionDefinition d){((Map<String,Object>)tx).putAll(row);}
            protected void doCommit(DefaultTransactionStatus s){}
            @SuppressWarnings("unchecked") protected void doRollback(DefaultTransactionStatus s){row.clear();row.putAll((Map<String,Object>)s.getTransaction());}
        };
        pay=mock(com.simlect.api.support.PayFeignSupport.class);
        service=new AfterSaleWorkflowService(mapper,application,orders,items,redis,pay,manager);
        ReflectionTestUtils.setField(service,"mockEnabled",true);ReflectionTestUtils.setField(service,"refundEnabled",true);
        ReflectionTestUtils.setField(service,"returnAddress","演示仓库，不得实际寄件");
        when(mapper.bindReference(anyString(),anyString())).thenAnswer(i->{row.put("refundReference",i.getArgument(1));return 1;});
        when(mapper.detail(id)).thenAnswer(i->new HashMap<>(row));when(mapper.lockRequest(id)).thenReturn(id);
        when(mapper.status(anyString(),anyString(),anyString())).thenAnswer(i->{if(!Objects.equals(row.get("status"),i.getArgument(1)))return 0;row.put("status",i.getArgument(2));return 1;});
        when(mapper.phase(anyString(),anyString(),anyString())).thenAnswer(i->{if(!Objects.equals(row.get("phase"),i.getArgument(1)))return 0;row.put("phase",i.getArgument(2));return 1;});
        when(mapper.inspection(anyString(),anyString(),anyBoolean())).thenAnswer(i->{row.put("restockable",i.getArgument(2));return 1;});
        when(mapper.result(anyString(),any(),anyString(),anyBoolean())).thenAnswer(i->{row.put("refundAmount",i.getArgument(1));row.put("refundReference",i.getArgument(2));return 1;});
        order=new OrderInfo();order.setUserId("u1");order.setOrderId("o1");order.setPayOrderId("p1");order.setOrderStatus(1);order.setChannelOrderId("LOCAL_DEMO_p1");
        item=new OrderItem();item.setOrderId("o1");item.setOrderItemId("o1_1");item.setOrderItemStatus(1);
        when(orders.getOrderInfoByOrderId("o1")).thenReturn(order);when(orders.findListByParam(any())).thenAnswer(i->List.of(order));when(items.getOrderItemByOrderItemId("o1_1")).thenReturn(item);
        doAnswer(i->((Callable<?>)i.getArgument(1)).call()).when(redis).runWithPayOrderLifecycleLock(anyString(),ArgumentMatchers.<Callable<Map<String,Object>>>any());
        doAnswer(i->{order.setOrderStatus(6);item.setOrderItemStatus(0);item.setRefundAmount(new BigDecimal("199.00"));item.setRefundOrderId((String)row.get("refundReference"));return null;}).when(orders).refundAfterSale(any(),anyString(),anyBoolean());
    }
    void approve(){service.review("admin",new AfterSaleDTO.Review(id,true,"核实通过"));}
    @Test void refundApprovalDoesNotPay(){approve();assertEquals("READY_REFUND",row.get("phase"));verify(orders,never()).refundAfterSale(any(),anyString(),anyBoolean());}
    @Test void rejectReleasesApplication(){service.review("admin",new AfterSaleDTO.Review(id,false,"证据不符"));assertEquals("REJECTED",row.get("status"));}
    @Test void completedCannotReview(){row.put("status","COMPLETED");assertThrows(BusinessException.class,()->approve());}
    @Test void exchangeCannotBeFalselyApproved(){row.put("type","exchange");assertThrows(BusinessException.class,()->approve());}
    @Test void approvalRechecksActualOrder(){order.setOrderStatus(5);assertThrows(BusinessException.class,()->approve());}
    @Test void returnNeedsConfiguredAddress(){row.put("type","return_refund");order.setOrderStatus(2);ReflectionTestUtils.setField(service,"returnAddress","");assertThrows(BusinessException.class,()->approve());}
    @Test void otherUserCannotReadOrReturn(){assertThrows(BusinessException.class,()->service.detailOwned("other",id));assertThrows(BusinessException.class,()->service.submitReturn("other",new AfterSaleDTO.ReturnParcel(id,"demo","DEMO123456")));}
    @Test void cannotReturnBeforeApproval(){assertThrows(BusinessException.class,()->service.submitReturn("u1",new AfterSaleDTO.ReturnParcel(id,"demo","DEMO123456")));}
    @Test void returnAndDamagedInspectionBeforeRefund(){row.put("type","return_refund");order.setOrderStatus(3);approve();assertEquals("WAIT_RETURN",row.get("phase"));service.submitReturn("u1",new AfterSaleDTO.ReturnParcel(id,"demo","DEMO123456"));assertEquals("WAIT_INSPECTION",row.get("phase"));service.inspect("admin",new AfterSaleDTO.Inspect(id,true,false,"商品损坏，不可再售"));assertEquals("READY_REFUND",row.get("phase"));service.refund("admin",id);verify(orders).refundAfterSale(item,"u1",false);assertEquals("COMPLETED",row.get("status"));}
    @Test void inspectionCannotSkipReturn(){row.put("type","return_refund");order.setOrderStatus(2);approve();assertThrows(BusinessException.class,()->service.inspect("admin",new AfterSaleDTO.Inspect(id,true,true,"检查")));}
    @Test void inspectionRejectDoesNotPay(){row.put("type","return_refund");order.setOrderStatus(2);approve();service.submitReturn("u1",new AfterSaleDTO.ReturnParcel(id,"demo","DEMO123456"));service.inspect("admin",new AfterSaleDTO.Inspect(id,false,false,"包裹不匹配"));assertEquals("REJECTED",row.get("status"));verify(orders,never()).refundAfterSale(any(),anyString(),anyBoolean());}
    @Test void refundCannotSkipReview(){assertThrows(BusinessException.class,()->service.refund("admin",id));verify(orders,never()).refundAfterSale(any(),anyString(),anyBoolean());}
    @Test void modeMismatchRejected(){approve();order.setChannelOrderId("real-provider");assertThrows(BusinessException.class,()->service.refund("admin",id));verify(orders,never()).refundAfterSale(any(),anyString(),anyBoolean());}
    @Test void repeatRefundCallsBusinessOnlyOnce(){approve();service.refund("admin",id);service.refund("admin",id);verify(orders,times(1)).refundAfterSale(item,"u1",true);assertEquals("COMPLETED",row.get("phase"));}
    @Test void refundFailureKeepsStableRetryStage(){approve();doThrow(new BusinessException("test failure")).when(orders).refundAfterSale(any(),anyString(),anyBoolean());assertThrows(BusinessException.class,()->service.refund("admin",id));assertEquals("APPROVED",row.get("status"));assertEquals("REFUND_FAILED",row.get("phase"));}
    @Test void shippedRefundOnlyRequiresReturn(){approve();order.setOrderStatus(2);assertThrows(BusinessException.class,()->service.refund("admin",id));verify(orders,never()).refundAfterSale(any(),anyString(),anyBoolean());}
    @Test void featureDisabled(){approve();ReflectionTestUtils.setField(service,"refundEnabled",false);assertThrows(BusinessException.class,()->service.refund("admin",id));}
    @Test void noFalseCompletionWhenBusinessDoesNotRefund(){approve();doNothing().when(orders).refundAfterSale(any(),anyString(),anyBoolean());assertThrows(BusinessException.class,()->service.refund("admin",id));assertNotEquals("COMPLETED",row.get("status"));}
    @Test void partiallyRefundedShippedCannotBypassReturn() {order.setOrderStatus(7);when(mapper.logisticsStatus("o1")).thenReturn(1);assertThrows(BusinessException.class,()->approve());}
    @Test void partiallyRefundedUnshippedCanRefund() {order.setOrderStatus(7);when(mapper.logisticsStatus("o1")).thenReturn(0);approve();assertEquals("READY_REFUND",row.get("phase"));}
    @Test void paymentRecordUpdatedOnlyOnFullCombinedRefund() {approve();service.refund("admin",id);verify(pay).markRefunded("p1");}
    @Test void partialCombinedRefundDoesNotMarkWholePaymentRefunded() {approve();OrderInfo sibling=new OrderInfo();sibling.setOrderStatus(1);when(orders.findListByParam(any())).thenAnswer(i->List.of(order,sibling));service.refund("admin",id);verify(pay,never()).markRefunded(anyString());}
    @Test void durableRefundReferenceReusedAfterRedisExpiry() {approve();row.put("refundReference","persisted-original");service.refund("admin",id);verify(redis).bindRefundOrderIdAnchor("o1_1","persisted-original");}
    @Test void conflictingRefundAnchorCannotExecute() {approve();row.put("refundReference","original");when(redis.getRefundOrderIdAnchor("o1_1")).thenReturn("other");assertThrows(BusinessException.class,()->service.refund("admin",id));verify(orders,never()).refundAfterSale(any(),anyString(),anyBoolean());}
    @Test void refundAlreadyStartedCannotBeRejectedAndReleased() {approve();row.put("refundReference","stable");assertThrows(BusinessException.class,()->service.review("admin",new AfterSaleDTO.Review(id,false,"取消")));assertEquals("APPROVED",row.get("status"));}
    @Test void approvedReturnCannotRefundBeforeParcelOrInspection() {
        row.put("type", "return_refund");
        order.setOrderStatus(2);
        approve();
        assertThrows(BusinessException.class, () -> service.refund("admin", id));
        service.submitReturn("u1", new AfterSaleDTO.ReturnParcel(id, "demo", "DEMO123456"));
        assertThrows(BusinessException.class, () -> service.refund("admin", id));
        assertEquals("WAIT_INSPECTION", row.get("phase"));
        verify(orders, never()).refundAfterSale(any(), anyString(), anyBoolean());
        verifyNoInteractions(pay);
    }
    @Test void inspectedResellableReturnRefundsOnlyOnceAndRestocks() {
        row.put("type", "return_refund");
        order.setOrderStatus(2);
        approve();
        service.submitReturn("u1", new AfterSaleDTO.ReturnParcel(id, "demo", "DEMO123456"));
        service.inspect("admin", new AfterSaleDTO.Inspect(id, true, true, "验货通过，可再次销售"));
        service.refund("admin", id);
        service.refund("admin", id);
        verify(orders, times(1)).refundAfterSale(item, "u1", true);
        assertEquals("COMPLETED", row.get("phase"));
    }
    @Test void unshippedOrderWithShippedLogisticsCannotUseRefundOnlyReview() {
        when(mapper.logisticsStatus("o1")).thenReturn(1);
        assertThrows(BusinessException.class, this::approve);
        verify(orders, never()).refundAfterSale(any(), anyString(), anyBoolean());
    }

}
