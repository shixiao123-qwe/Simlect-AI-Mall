package com.simlect.controller;
import com.simlect.annotation.GlobalInterceptor;
import com.simlect.biz.AfterSaleWorkflowService;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.vo.ResponseVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order/afterSale")
public class AfterSaleProgressController extends ABaseController {
    private final AfterSaleWorkflowService service;
    public AfterSaleProgressController(AfterSaleWorkflowService service) { this.service=service; }
    @PostMapping("/progress") @GlobalInterceptor(checkLogin=true)
    public ResponseVO progress() { return getSuccessResponseVO(service.mine(getTokenUserInfo().getUserId())); }
    @PostMapping("/detail") @GlobalInterceptor(checkLogin=true)
    public ResponseVO detail(@Valid @RequestBody AfterSaleDTO.Request dto) { return getSuccessResponseVO(service.detailOwned(getTokenUserInfo().getUserId(),dto.requestId())); }
    @PostMapping("/returnParcel") @GlobalInterceptor(checkLogin=true)
    public ResponseVO parcel(@Valid @RequestBody AfterSaleDTO.ReturnParcel dto) { return getSuccessResponseVO(service.submitReturn(getTokenUserInfo().getUserId(),dto)); }
}
