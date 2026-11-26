package com.simlect.controller;
import com.simlect.biz.AfterSaleService;
import com.simlect.controller.internal.AfterSaleInternalController;
import com.simlect.entity.dto.TokenUserInfoDTO;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone MVC tests validate DTO/identity wiring, not production auth filters. */
class AfterSaleControllerTest {
    AfterSaleService service; MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(AfterSaleService.class);
        AfterSaleController controller=spy(new AfterSaleController(service));
        TokenUserInfoDTO user=new TokenUserInfoDTO();user.setUserId("session-user");
        doReturn(user).when(controller).getTokenUserInfo();
        mvc=MockMvcBuilders.standaloneSetup(controller,new AfterSaleInternalController(service))
            .setControllerAdvice(new AGlobalExceptionHandlerController()).build();
    }
    @Test void createUsesSessionIdentity() throws Exception {
        mvc.perform(post("/order/afterSale/create").contentType("application/json")
                .content("{\"requestId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"orderItemId\":\"o1_1\",\"type\":\"refund\",\"reason\":\"broken\"}"))
            .andExpect(jsonPath("$.status").value("success"));
        verify(service).create(eq("session-user"),any());
    }
    @Test void resultUsesSessionIdentityAndOriginalPayload() throws Exception {
        when(service.result(eq("session-user"),any())).thenReturn(java.util.Map.of("state","NOT_FOUND","retryAllowed",true));
        mvc.perform(post("/order/afterSale/result").contentType("application/json")
                .content("{\"requestId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"orderItemId\":\"o1_1\",\"type\":\"refund\",\"reason\":\"broken\",\"userId\":\"forged\"}"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.data.state").value("NOT_FOUND"));
        verify(service).result(eq("session-user"),argThat(dto->"o1_1".equals(dto.orderItemId())));
        verify(service,never()).create(anyString(),any());
    }
    @Test void invalidResultRequestCannotReachService() throws Exception {
        mvc.perform(post("/order/afterSale/result").contentType("application/json")
                .content("{\"requestId\":\"bad\",\"orderItemId\":\"o1_1\",\"type\":\"refund\",\"reason\":\"broken\"}"))
            .andExpect(jsonPath("$.status").value("error"));
        verifyNoInteractions(service);
    }
    @Test void invalidPayloadDoesNotReachService() throws Exception {
        mvc.perform(post("/order/afterSale/create").contentType("application/json")
                .content("{\"requestId\":\"bad\",\"orderItemId\":\"\",\"type\":\"fake\",\"reason\":\" \"}"))
            .andExpect(jsonPath("$.status").value("error"));
        verifyNoInteractions(service);
    }
    @Test void missingInternalUserDoesNotReachService() throws Exception {
        mvc.perform(post("/internal/order/afterSale/eligibility").contentType("application/json")
                .content("{\"orderItemId\":\"o1_1\",\"type\":\"refund\"}"))
            .andExpect(jsonPath("$.status").value("error"));
        verifyNoInteractions(service);
    }
}
