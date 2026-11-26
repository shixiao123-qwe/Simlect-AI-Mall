package com.simlect.controller;

import com.simlect.biz.OrderItemService;
import com.simlect.biz.impl.OrderInfoServiceImpl;
import com.simlect.component.RedisComponent;
import com.simlect.api.support.PayFeignSupport;
import com.simlect.api.support.StockFeignSupport;
import com.simlect.entity.dto.TokenUserInfoDTO;
import com.simlect.entity.po.OrderInfo;
import com.simlect.entity.po.OrderItem;
import com.simlect.entity.query.OrderInfoQuery;
import com.simlect.entity.query.OrderItemQuery;
import com.simlect.mappers.AfterSaleMapper;
import com.simlect.mappers.OrderInfoMapper;
import com.simlect.mappers.OrderItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the actual refund service gate. Authentication filters are covered separately. */
class LegacyRefundSafetyControllerTest {
    @Test
    void shippedLegacyHttpRequestCannotChangePaymentItemOrInventory() throws Exception {
        OrderInfo order = new OrderInfo();
        order.setOrderId("o1"); order.setUserId("u1");
        order.setPayOrderId("p1"); order.setOrderStatus(2);
        OrderItem item = new OrderItem();
        item.setOrderId("o1"); item.setOrderItemId("o1_1"); item.setOrderItemStatus(1);
        order.setOrderItemList(List.of(item));
        @SuppressWarnings("unchecked")
        OrderInfoMapper<OrderInfo, OrderInfoQuery> orders = mock(OrderInfoMapper.class);
        @SuppressWarnings("unchecked")
        OrderItemMapper<OrderItem, OrderItemQuery> items = mock(OrderItemMapper.class);
        AfterSaleMapper applications = mock(AfterSaleMapper.class);
        PayFeignSupport pay = mock(PayFeignSupport.class);
        StockFeignSupport stock = mock(StockFeignSupport.class);
        RedisComponent redis = mock(RedisComponent.class);
        when(orders.selectList(any())).thenReturn(List.of(order));
        when(orders.selectForRefundByOrderId("o1")).thenReturn(order);
        when(applications.lockOrder("o1")).thenReturn(order);
        when(applications.lockItem("o1_1")).thenReturn(item);
        when(orders.selectRefundLogisticsStatus("o1")).thenReturn(1);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(1).run(); return null; })
                .when(redis).runWithPayOrderLifecycleLock(eq("p1"), any(Runnable.class));
        OrderInfoServiceImpl service = new OrderInfoServiceImpl();
        ReflectionTestUtils.setField(service, "orderInfoMapper", orders);
        ReflectionTestUtils.setField(service, "orderItemMapper", items);
        ReflectionTestUtils.setField(service, "afterSaleMapper", applications);
        ReflectionTestUtils.setField(service, "payFeignSupport", pay);
        ReflectionTestUtils.setField(service, "stockFeignSupport", stock);
        ReflectionTestUtils.setField(service, "redisComponent", redis);
        OrderController controller = spy(new OrderController());
        TokenUserInfoDTO user = new TokenUserInfoDTO(); user.setUserId("u1");
        doReturn(user).when(controller).getTokenUserInfo();
        OrderItemService itemService = mock(OrderItemService.class);
        when(itemService.getOrderItemByOrderItemId("o1_1")).thenReturn(item);
        ReflectionTestUtils.setField(controller, "orderInfoService", service);
        ReflectionTestUtils.setField(controller, "orderItemService", itemService);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController()).build();

        mvc.perform(post("/order/refundOrder").param("orderItemId", "o1_1"))
                .andExpect(jsonPath("$.status").value("error"));
        verifyNoInteractions(pay, stock);
        verify(items, never()).updateByParam(any(), any());
        verify(orders, never()).updateByParam(any(), any());
    }
}
