package com.simlect.controller;
import com.simlect.annotation.GlobalInterceptor;
import com.simlect.biz.LocalDemoPaymentService;
import com.simlect.entity.vo.ResponseVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order/localDemo")
public class LocalDemoPaymentController extends ABaseController {
    private final LocalDemoPaymentService service;
    public LocalDemoPaymentController(LocalDemoPaymentService service) { this.service=service; }
    public record Confirm(@NotBlank @Pattern(regexp="[A-Za-z0-9]{1,32}") String payOrderId) {}
    @PostMapping("/confirmPayment") @GlobalInterceptor(checkLogin=true)
    public ResponseVO confirm(@Valid @RequestBody Confirm dto) {
        return getSuccessResponseVO(service.confirm(getTokenUserInfo().getUserId(),dto.payOrderId()));
    }
}
