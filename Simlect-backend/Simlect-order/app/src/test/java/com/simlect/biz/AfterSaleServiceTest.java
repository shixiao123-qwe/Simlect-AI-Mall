package com.simlect.biz;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.po.*;
import com.simlect.entity.query.*;
import com.simlect.mappers.*;
import com.simlect.exception.BusinessException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AfterSaleServiceTest {
    OrderInfoMapper<OrderInfo,OrderInfoQuery> orders;
    OrderItemMapper<OrderItem,OrderItemQuery> items;
    AfterSaleMapper mapper;
    AfterSaleService service;
    OrderInfo order; OrderItem item;
    String id="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        orders=mock(OrderInfoMapper.class); items=mock(OrderItemMapper.class); mapper=mock(AfterSaleMapper.class);
        service=new AfterSaleService(orders,items,mapper);
        when(mapper.active(anyString())).thenReturn(null); when(mapper.byId(anyString())).thenReturn(null);
        order=new OrderInfo(); order.setOrderId("o1"); order.setUserId("u1"); order.setOrderStatus(1);
        item=new OrderItem(); item.setOrderId("o1"); item.setOrderItemId("o1_1"); item.setOrderItemStatus(1);
        when(orders.selectByOrderId("o1")).thenReturn(order);
        when(items.selectByOrderItemId("o1_1")).thenReturn(item);
        when(mapper.lockOrder("o1")).thenReturn(order); when(mapper.lockItem("o1_1")).thenReturn(item);
    }
    AfterSaleDTO.Create dto() { return new AfterSaleDTO.Create(id,"o1_1","refund","质量问题"); }
    @Test void returnSubmissionDoesNotClaimVerifiedWindow() {
        order.setOrderStatus(3);
        var result=service.check("u1","o1_1","return_refund");
        assertEquals(true,result.get("eligible"));
        assertEquals("APPLICATION_ONLY",result.get("eligibilityScope"));
        assertEquals("POLICY_MISSING",result.get("returnWindowStatus"));
        assertEquals(true,result.get("manualReviewRequired"));
        assertEquals("UNCONFIGURED",result.get("freightStatus"));
        assertEquals("UNCONFIGURED",result.get("refundArrivalStatus"));
    }
    @Test void newPolicyMetadataDoesNotDiscloseOthersOrder() {
        assertThrows(BusinessException.class,()->service.check("another-user","o1_1","return_refund"));
        verifyNoInteractions(mapper);
    }
    @Test void activeApplicationStillRejectedWithMissingPolicy() {
        order.setOrderStatus(3);when(mapper.active("o1_1")).thenReturn(Map.of("requestId",id));
        assertEquals(false,service.check("u1","o1_1","return_refund").get("eligible"));
    }
    @ParameterizedTest @CsvSource({"0,refund,false","1,refund,true","2,refund,false","3,refund,false","4,refund,false","6,refund,false","7,refund,true","2,return_refund,true","3,exchange,true","1,return_refund,false"})
    void statusMatrix(int status,String type,boolean expected) {
        order.setOrderStatus(status);
        assertEquals(expected,service.check("u1","o1_1",type).get("eligible"));
        verify(mapper,never()).insert(any());
    }
    @Test void ownership() {
        assertThrows(BusinessException.class,()->service.create("other",dto()));
        verify(mapper,never()).insert(any());
    }
    @Test void missingItem() { assertThrows(BusinessException.class,()->service.check("u1","missing","refund")); }
    @Test void refundedItem() { item.setOrderItemStatus(0); assertEquals(false,service.check("u1","o1_1","refund").get("eligible")); }
    @Test void activeRequestBlocks() {
        when(mapper.active("o1_1")).thenReturn(Map.of("requestId","previous"));
        assertThrows(BusinessException.class,()->service.create("u1",dto())); verify(mapper,never()).insert(any());
    }
    @Test void createPersistsRequestAndAuditOnly() {
        Map<String,Object> result=service.create("u1",dto());
        assertEquals("PENDING_REVIEW",result.get("status"));
        var sequence=inOrder(mapper); sequence.verify(mapper).insert(result); sequence.verify(mapper).insertLog(result);
    }
    @Test void replayReturnsSame() {
        Map<String,Object> old=Map.of("requestId",id,"userId","u1","orderItemId","o1_1","type","refund","reason","质量问题","status","PENDING_REVIEW");
        when(mapper.byId(id)).thenReturn(old); assertSame(old,service.create("u1",dto()));
        verify(mapper,never()).insert(any()); verify(mapper,never()).insertLog(any());
    }
    @Test void reusedIdDifferentPayloadRejected() {
        when(mapper.byId(id)).thenReturn(Map.of("userId","u1","orderItemId","o1_1","type","exchange","reason","质量问题"));
        assertThrows(BusinessException.class,()->service.create("u1",dto()));
    }
    @Test void statusRecheckedAfterLock() {
        OrderInfo closed=new OrderInfo(); closed.setOrderId("o1"); closed.setUserId("u1"); closed.setOrderStatus(5);
        when(mapper.lockOrder("o1")).thenReturn(closed);
        assertThrows(BusinessException.class,()->service.create("u1",dto())); verify(mapper,never()).insert(any());
    }
    @Test void recoveryReturnsOriginalEvenAfterReviewProgressed() {
        Map<String,Object> old=Map.of("requestId",id,"userId","u1","orderItemId","o1_1",
                "type","refund","reason","质量问题","status","APPROVED");
        when(mapper.byId(id)).thenReturn(old);
        var result=service.result("u1",dto());
        assertEquals("SUCCEEDED",result.get("state"));assertSame(old,result.get("application"));
        assertEquals(false,result.get("retryAllowed"));verify(mapper,never()).insert(any());
    }
    @Test void recoveryMissingMayRetryOnlyOriginalEligibleProposal() {
        var result=service.result("u1",dto());
        assertEquals("NOT_FOUND",result.get("state"));assertEquals(id,result.get("requestId"));
        assertEquals(true,result.get("retryAllowed"));verify(mapper,never()).insert(any());
    }
    @Test void recoveryDoesNotOfferRetryWithAnotherActiveApplication() {
        when(mapper.active("o1_1")).thenReturn(Map.of("requestId","another"));
        assertEquals(false,service.result("u1",dto()).get("retryAllowed"));
        verify(mapper,never()).insert(any());
    }
    @Test void recoveryRejectsOtherUserBeforeLookingUpRequest() {
        assertThrows(BusinessException.class,()->service.result("other",dto()));
        verify(mapper,never()).byId(anyString());verify(mapper,never()).insert(any());
    }
    @Test void recoveryRejectsSameRequestIdWithAlteredPayload() {
        when(mapper.byId(id)).thenReturn(Map.of("requestId",id,"userId","u1","orderItemId","o1_1",
                "type","refund","reason","different","status","PENDING_REVIEW"));
        assertThrows(BusinessException.class,()->service.result("u1",dto()));
        verify(mapper,never()).insert(any());
    }
    @Test void newRequestIdCannotBypassActiveApplicationAfterUnknown() {
        when(mapper.active("o1_1")).thenReturn(Map.of("requestId",id));
        var changed=new AfterSaleDTO.Create("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","o1_1","refund","质量问题");
        assertThrows(BusinessException.class,()->service.create("u1",changed));
        verify(mapper,never()).insert(any());
    }
    @Test void validationRejectsInvalidPayload() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertEquals(0,validator.validate(dto()).size());
            assertFalse(validator.validate(new AfterSaleDTO.Create("bad","","fake"," ")).isEmpty());
            assertFalse(validator.validate(new AfterSaleDTO.Check("u1","o1_1","fake")).isEmpty());
        }
    }
}
