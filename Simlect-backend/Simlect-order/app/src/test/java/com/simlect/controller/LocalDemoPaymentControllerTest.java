package com.simlect.controller;
import com.simlect.biz.LocalDemoPaymentService;
import com.simlect.entity.dto.TokenUserInfoDTO;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class LocalDemoPaymentControllerTest {
    LocalDemoPaymentService service;MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(LocalDemoPaymentService.class);
        LocalDemoPaymentController controller=spy(new LocalDemoPaymentController(service));
        TokenUserInfoDTO user=new TokenUserInfoDTO();user.setUserId("session-user");
        doReturn(user).when(controller).getTokenUserInfo();
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new AGlobalExceptionHandlerController()).build();
    }
    @Test void identityFromSessionOnly() throws Exception {
        when(service.confirm("session-user","p1")).thenReturn(Map.of("simulated",true));
        mvc.perform(post("/order/localDemo/confirmPayment").contentType("application/json")
                .content("{\"payOrderId\":\"p1\",\"userId\":\"forged\",\"amount\":0.01}"))
            .andExpect(jsonPath("$.data.simulated").value(true));
        verify(service).confirm("session-user","p1");
    }
    @Test void badIdRejectedBeforeService() throws Exception {
        mvc.perform(post("/order/localDemo/confirmPayment").contentType("application/json").content("{\"payOrderId\":\"\"}"))
            .andExpect(jsonPath("$.status").value("error"));verifyNoInteractions(service);
    }
    @Test void loginAnnotationRequired() throws Exception {
        var annotation=LocalDemoPaymentController.class.getMethod("confirm",LocalDemoPaymentController.Confirm.class)
                .getAnnotation(com.simlect.annotation.GlobalInterceptor.class);
        org.junit.jupiter.api.Assertions.assertTrue(annotation.checkLogin());
    }
}
