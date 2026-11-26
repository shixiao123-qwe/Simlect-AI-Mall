package com.simlect.controller;
import com.simlect.annotation.GlobalInterceptor;
import com.simlect.biz.AfterSaleService;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.vo.ResponseVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order/afterSale")
public class AfterSaleController extends ABaseController {
    private final AfterSaleService service;
    public AfterSaleController(AfterSaleService service) { this.service=service; }
    @PostMapping("/create") @GlobalInterceptor(checkLogin=true)
    public ResponseVO create(@Valid @RequestBody AfterSaleDTO.Create dto) {
        return getSuccessResponseVO(service.create(getTokenUserInfo().getUserId(),dto));
    }
    @PostMapping("/result") @GlobalInterceptor(checkLogin=true)
    public ResponseVO result(@Valid @RequestBody AfterSaleDTO.Create dto) {
        return getSuccessResponseVO(service.result(getTokenUserInfo().getUserId(),dto));
    }
    @PostMapping("/mine") @GlobalInterceptor(checkLogin=true)
    public ResponseVO mine() { return getSuccessResponseVO(service.mine(getTokenUserInfo().getUserId())); }
}
