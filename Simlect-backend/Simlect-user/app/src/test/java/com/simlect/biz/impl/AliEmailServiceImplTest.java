package com.simlect.biz.impl;

import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AliEmailServiceImplTest {
    @Test
    void localModeLogsGeneratedCodeWithoutCreatingMailClient(CapturedOutput output) {
        AliEmailServiceImpl service = new AliEmailServiceImpl();
        ReflectionTestUtils.setField(service, "localMockEnabled", true);
        try (var clients = mockConstruction(DefaultAcsClient.class)) {
            service.validateConfiguration();
            String code = service.sendVerificationCode("demo@example.test");
            assertTrue(code.matches("[0-9]{6}"));
            assertTrue(output.getOut().contains("收件邮箱=demo@example.test 验证码=" + code));
            assertTrue(output.getOut().contains("未发送真实邮件"));
            assertTrue(clients.constructed().isEmpty());
        }
    }

    @Test
    void productionRejectsLocalModeAtStartup() {
        AliEmailServiceImpl service = new AliEmailServiceImpl();
        ReflectionTestUtils.setField(service, "localMockEnabled", true);
        ReflectionTestUtils.setField(service, "productionReady", true);
        assertThrows(IllegalStateException.class, service::validateConfiguration);
    }

    @Test
    void productionRejectsLocalModeBeforeLoggingCode(CapturedOutput output) {
        AliEmailServiceImpl service = new AliEmailServiceImpl();
        ReflectionTestUtils.setField(service, "localMockEnabled", true);
        ReflectionTestUtils.setField(service, "productionReady", true);
        try (var clients = mockConstruction(DefaultAcsClient.class)) {
            assertThrows(IllegalStateException.class,
                    () -> service.sendVerificationCode("demo@example.test"));
            assertFalse(output.getOut().contains("验证码="));
            assertTrue(clients.constructed().isEmpty());
        }
    }

    @Test
    void defaultModeStillUsesMailProviderWithoutLoggingCode(CapturedOutput output) throws Exception {
        AliEmailServiceImpl service = new AliEmailServiceImpl();
        ReflectionTestUtils.setField(service, "accessKeyId", "test-id");
        ReflectionTestUtils.setField(service, "accessKeySecret", "test-secret");
        ReflectionTestUtils.setField(service, "accountName", "sender@example.test");
        ReflectionTestUtils.setField(service, "replyToAddress", "false");
        CommonResponse response = new CommonResponse();
        response.setData("{\"RequestId\":\"test\"}");
        try (var clients = mockConstruction(DefaultAcsClient.class,
                (client, context) -> when(client.getCommonResponse(any(CommonRequest.class)))
                        .thenReturn(response))) {
            service.validateConfiguration();
            String code = service.sendVerificationCode("demo@example.test");
            assertTrue(code.matches("[0-9]{6}"));
            assertEquals(1, clients.constructed().size());
            verify(clients.constructed().get(0)).getCommonResponse(any(CommonRequest.class));
            assertFalse(output.getOut().contains("验证码="));
        }
    }
}
