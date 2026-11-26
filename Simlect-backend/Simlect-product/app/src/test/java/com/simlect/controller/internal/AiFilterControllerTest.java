package com.simlect.controller.internal;
import com.simlect.biz.*;
import com.simlect.controller.AGlobalExceptionHandlerController;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@ExtendWith(MockitoExtension.class)
class AiFilterControllerTest {
    @Mock AiProductFilterService service;
    @InjectMocks ProductInternalController controller;
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new AGlobalExceptionHandlerController()).build(); }
    @Test void validRequestDelegates() throws Exception {
        mvc.perform(post("/internal/product/ai/filter").contentType("application/json").content("{\"productIds\":[\"p1\"],\"maxPrice\":300}"))
            .andExpect(jsonPath("$.status").value("success"));verify(service).filter(any());
    }
    @Test void invalidBudgetAndEmptyCandidatesDoNotReachService() throws Exception {
        for(String body:java.util.List.of("{\"productIds\":[]}","{\"productIds\":[\"p\"],\"maxPrice\":-1}",
                "{\"productIds\":[\"\"]}","{\"productIds\":[\"p\"],\"hardAttributes\":[{\"name\":\"\",\"value\":\"全新\"}]}")) {
            mvc.perform(post("/internal/product/ai/filter").contentType("application/json").content(body))
                .andExpect(jsonPath("$.status").value("error"));
        }
        verifyNoInteractions(service);
    }
}
