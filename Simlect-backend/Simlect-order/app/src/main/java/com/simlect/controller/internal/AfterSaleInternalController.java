package com.simlect.controller.internal;
import com.simlect.controller.ABaseController;
import com.simlect.biz.AfterSaleService;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.vo.ResponseVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Existing InternalApiAuthFilter protects this read-only endpoint. */
@RestController
@RequestMapping("/internal/order/afterSale")
public class AfterSaleInternalController extends ABaseController {
    private final AfterSaleService service;
    public AfterSaleInternalController(AfterSaleService service) { this.service=service; }
    @PostMapping("/eligibility")
    public ResponseVO check(@Valid @RequestBody AfterSaleDTO.Check dto) {
        return getSuccessResponseVO(service.check(dto.userId(),dto.orderItemId(),dto.type()));
    }
}
