package com.simlect.controller;
import com.simlect.biz.AfterSaleWorkflowService;
import com.simlect.controller.admin.AfterSaleAdminController;
import com.simlect.component.RedisComponent;
import com.simlect.utils.AuthCookieHelper;
import com.simlect.aspect.AdminSensitiveConfirmAspect;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AfterSaleWorkflowControllerTest {
    AfterSaleWorkflowService service;RedisComponent redis;AuthCookieHelper cookies;MockMvc mvc;
    String id="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @BeforeEach void setup(){
        service=mock(AfterSaleWorkflowService.class);redis=mock(RedisComponent.class);cookies=mock(AuthCookieHelper.class);
        when(cookies.resolveAdminToken(any())).thenReturn("admin-token");when(redis.getLoginInfo4Admin("admin-token")).thenReturn("admin");
        var controller=new AfterSaleAdminController(service,redis,cookies);
        var aspect=new AdminSensitiveConfirmAspect();ReflectionTestUtils.setField(aspect,"redisComponent",redis);ReflectionTestUtils.setField(aspect,"authCookieHelper",cookies);
        var factory=new AspectJProxyFactory(controller);factory.setProxyTargetClass(true);factory.addAspect(aspect);
        mvc=MockMvcBuilders.standaloneSetup((Object)factory.getProxy()).setControllerAdvice(new AGlobalExceptionHandlerController()).build();
    }
    String body(){return "{\"requestId\":\""+id+"\"}";}
    @Test void listRequiresAdminEvenWithoutGlobalInterceptor() throws Exception {
        when(cookies.resolveAdminToken(any())).thenReturn(null);
        mvc.perform(post("/admin/order/afterSale/list").contentType("application/json").content("{}"))
            .andExpect(jsonPath("$.code").value(901));verifyNoInteractions(service);
    }
    @Test void refundWithoutSensitiveTicketRejected() throws Exception {
        mvc.perform(post("/admin/order/afterSale/refund").contentType("application/json").content(body()))
            .andExpect(jsonPath("$.status").value("error"));verify(service,never()).refund(anyString(),anyString());
    }
    @Test void ticketReplayCannotRunRefundTwice() throws Exception {
        when(redis.consumeAdminConfirmToken("nonce","admin","/admin/order/afterSale/refund")).thenReturn(true,false);
        when(service.refund("admin",id)).thenReturn(Map.of("status","COMPLETED"));
        mvc.perform(post("/admin/order/afterSale/refund").header("X-Admin-Confirm-Token","nonce").contentType("application/json").content(body()))
            .andExpect(jsonPath("$.status").value("success"));
        mvc.perform(post("/admin/order/afterSale/refund").header("X-Admin-Confirm-Token","nonce").contentType("application/json").content(body()))
            .andExpect(jsonPath("$.status").value("error"));verify(service,times(1)).refund("admin",id);
    }
    @Test void crossActionTicketRejected() throws Exception {
        when(redis.consumeAdminConfirmToken(anyString(),anyString(),anyString())).thenReturn(false);
        mvc.perform(post("/admin/order/afterSale/review").header("X-Admin-Confirm-Token","refund-nonce").contentType("application/json")
            .content("{\"requestId\":\""+id+"\",\"approved\":true,\"note\":\"test\"}"))
            .andExpect(jsonPath("$.status").value("error"));verify(service,never()).review(anyString(),any());
    }
    @Test void invalidReturnTrackingRejected() throws Exception {
        var user=spy(new AfterSaleProgressController(service));var session=new com.simlect.entity.dto.TokenUserInfoDTO();session.setUserId("u1");doReturn(session).when(user).getTokenUserInfo();
        var web=MockMvcBuilders.standaloneSetup(user).setControllerAdvice(new AGlobalExceptionHandlerController()).build();
        web.perform(post("/order/afterSale/returnParcel").contentType("application/json")
            .content("{\"requestId\":\""+id+"\",\"carrier\":\"demo\",\"tracking\":\"bad\"}"))
            .andExpect(jsonPath("$.status").value("error"));verify(service,never()).submitReturn(anyString(),any());
    }
}
