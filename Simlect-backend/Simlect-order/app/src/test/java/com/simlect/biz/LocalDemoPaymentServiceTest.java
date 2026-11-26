package com.simlect.biz;
import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.component.RedisComponent;
import com.simlect.entity.config.AppConfig;
import com.simlect.entity.po.OrderInfo;
import com.simlect.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LocalDemoPaymentServiceTest {
    OrderInfoService orders;RedisComponent redis;AppConfig config;LocalDemoPaymentService service;OrderInfo row;
    @BeforeEach void setup() throws Exception {
        orders=mock(OrderInfoService.class);redis=mock(RedisComponent.class);config=mock(AppConfig.class);
        service=new LocalDemoPaymentService(orders,redis,config);
        ReflectionTestUtils.setField(service,"mockEnabled",true);ReflectionTestUtils.setField(service,"confirmEnabled",true);
        when(config.getOrderExpireMinute()).thenReturn(15);
        row=new OrderInfo();row.setOrderId("o1");row.setUserId("u1");row.setPayOrderId("p1");
        row.setOrderStatus(0);row.setOrderTime(new Date());row.setAmount(new BigDecimal("199.00"));
        when(orders.findListByParam(any())).thenReturn(List.of(row));
        doAnswer(i->((Callable<?>)i.getArgument(1)).call()).when(redis)
            .runWithPayOrderLifecycleLock(anyString(),ArgumentMatchers.<Callable<Map<String,Object>>>any());
        doAnswer(i->{PayOrderNotifyDTO n=i.getArgument(0);row.setOrderStatus(1);row.setChannelOrderId(n.getChannelOrderId());return null;})
            .when(orders).paySuccess(any());
    }
    @Test void defaultDisabled() {
        ReflectionTestUtils.setField(service,"confirmEnabled",false);
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));verifyNoInteractions(orders);
    }
    @Test void productionForbiddenAtStartupAndRuntime() {
        ReflectionTestUtils.setField(service,"productionReady",true);
        assertThrows(IllegalStateException.class,service::validateConfiguration);
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
    }
    @Test void requiresMockMode() {
        ReflectionTestUtils.setField(service,"mockEnabled",false);
        assertThrows(IllegalStateException.class,service::validateConfiguration);
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
    }
    @Test void ownershipIncludesEveryChildOrder() {
        OrderInfo other=new OrderInfo();other.setUserId("other");
        when(orders.findListByParam(any())).thenReturn(List.of(row,other));
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));verify(orders,never()).paySuccess(any());
    }
    @Test void missingOrder() {
        when(orders.findListByParam(any())).thenReturn(List.of());
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
    }
    @ParameterizedTest @ValueSource(ints={-1,4,5,6,7}) void closedStatesRejected(int status) {
        row.setOrderStatus(status);assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
        verify(orders,never()).paySuccess(any());
    }
    @Test void expiredRejectedBeforeMqArrives() {
        row.setOrderTime(new Date(System.currentTimeMillis()-16*60_000L));
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));verify(orders,never()).paySuccess(any());
    }
    @Test void closeMarkerRejected() {
        when(redis.isPayOrderCloseMarked("p1")).thenReturn(true);
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
    }
    @Test void realInitiatedTradeRejected() {
        when(redis.isPayTradeInitiated("p1")).thenReturn(true);
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));verify(orders,never()).paySuccess(any());
    }
    @Test void amountMustComeFromServerAndCallbackIsIdempotent() {
        assertEquals(false,service.confirm("u1","p1").get("alreadyPaid"));
        var captor=org.mockito.ArgumentCaptor.forClass(PayOrderNotifyDTO.class);
        verify(orders).paySuccess(captor.capture());
        assertEquals(new BigDecimal("199.00"),captor.getValue().getTotalAmount());
        assertEquals("LOCAL_DEMO_p1",captor.getValue().getChannelOrderId());
        assertEquals(true,service.confirm("u1","p1").get("alreadyPaid"));
        verify(orders,times(1)).paySuccess(any());
    }
    @Test void nonDemoPaidOrderNotRewritten() {
        row.setOrderStatus(1);row.setChannelOrderId("real-channel");
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));verify(orders,never()).paySuccess(any());
    }
    @Test void callbackNotAppliedCannotReportSuccess() {
        doNothing().when(orders).paySuccess(any());
        assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
    }
    @Test void invalidAmountAndStatusFailClosed() {
        row.setAmount(null);assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
        row.setOrderStatus(null);assertThrows(BusinessException.class,()->service.confirm("u1","p1"));
    }
}
