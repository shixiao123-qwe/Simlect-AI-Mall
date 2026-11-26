package com.simlect.controller.internal;
import com.simlect.controller.ABaseController;
import com.simlect.biz.AfterSaleWorkflowService;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.vo.ResponseVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/internal/order/afterSale")
public class AfterSaleProgressInternalController extends ABaseController {
    private final AfterSaleWorkflowService service;
    public AfterSaleProgressInternalController(AfterSaleWorkflowService service) { this.service=service; }
    @PostMapping("/requests")
    public ResponseVO requests(@Valid @RequestBody AfterSaleDTO.User dto) { return getSuccessResponseVO(service.mine(dto.userId())); }
}
