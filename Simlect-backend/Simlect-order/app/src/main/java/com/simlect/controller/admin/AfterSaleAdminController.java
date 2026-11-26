package com.simlect.controller.admin;
import com.simlect.annotation.AdminSensitiveConfirm;
import com.simlect.biz.AfterSaleWorkflowService;
import com.simlect.component.RedisComponent;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.exception.BusinessException;
import com.simlect.utils.AuthCookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/order/afterSale")
public class AfterSaleAdminController extends ABaseController {
    private final AfterSaleWorkflowService service;
    private final RedisComponent redis;
    private final AuthCookieHelper cookies;
    public AfterSaleAdminController(AfterSaleWorkflowService service,RedisComponent redis,AuthCookieHelper cookies) {
        this.service=service;this.redis=redis;this.cookies=cookies;
    }
    private String operator(HttpServletRequest request) {
        String token=cookies.resolveAdminToken(request);
        Object account=token==null || token.isBlank()?null:redis.getLoginInfo4Admin(token);
        if (account==null) throw new BusinessException(ResponseCodeEnum.CODE_901);
        return String.valueOf(account);
    }
    public record ListRequest(@Pattern(regexp="PENDING_REVIEW|APPROVED|REJECTED|COMPLETED") String status,@Min(1) @Max(200) Integer pageNo) {}
    @PostMapping("/list")
    public ResponseVO list(HttpServletRequest request,@Valid @RequestBody ListRequest dto) {
        operator(request);return getSuccessResponseVO(Map.of("list",service.list(dto.status(),dto.pageNo()==null?1:dto.pageNo()),"simulated",service.simulated()));
    }
    @PostMapping("/detail")
    public ResponseVO detail(HttpServletRequest request,@Valid @RequestBody AfterSaleDTO.Request dto) {
        operator(request);return getSuccessResponseVO(service.detail(dto.requestId()));
    }
    @PostMapping("/review") @AdminSensitiveConfirm
    public ResponseVO review(HttpServletRequest request,@Valid @RequestBody AfterSaleDTO.Review dto) {
        return getSuccessResponseVO(service.review(operator(request),dto));
    }
    @PostMapping("/inspect") @AdminSensitiveConfirm
    public ResponseVO inspect(HttpServletRequest request,@Valid @RequestBody AfterSaleDTO.Inspect dto) {
        return getSuccessResponseVO(service.inspect(operator(request),dto));
    }
    @PostMapping("/refund") @AdminSensitiveConfirm
    public ResponseVO refund(HttpServletRequest request,@Valid @RequestBody AfterSaleDTO.Request dto) {
        return getSuccessResponseVO(service.refund(operator(request),dto.requestId()));
    }
}
