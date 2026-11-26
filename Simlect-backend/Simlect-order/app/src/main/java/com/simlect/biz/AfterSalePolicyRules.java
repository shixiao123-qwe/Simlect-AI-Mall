package com.simlect.biz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;

/** Application eligibility is distinct from a verified return-window decision. */
@Component
public class AfterSalePolicyRules {
    @Value("${simlect.after-sale.return-window-days:0}") private int days;
    @Value("${simlect.after-sale.return-window-source:}") private String source = "";

    public enum Applicability { STANDARD, SPECIAL, UNKNOWN }
    public record Receipt(Instant signedAt, boolean verified) {
        public static Receipt unavailable() { return new Receipt(null, false); }
    }
    public record Decision(String status, boolean manualReviewRequired, String reason) {}

    public Decision evaluate(String type, Receipt receipt, Applicability applicability, Instant now) {
        if ("refund".equals(type)) return result("NOT_APPLICABLE", false, "未发货退款不使用签收退货期限规则");
        if (days < 1 || days > 365 || source == null || source.isBlank())
            return result("POLICY_MISSING", true, "未配置有来源的正式退货期限，需人工核实");
        if (receipt == null || receipt.signedAt() == null || !receipt.verified())
            return result("RECEIPT_MISSING", true, "缺少可靠签收时间，不能判断是否在退货期限内");
        if (now == null || receipt.signedAt().isAfter(now))
            return result("RECEIPT_INVALID", true, "签收时间异常，需人工核实");
        if (applicability != Applicability.STANDARD)
            return result(applicability == Applicability.SPECIAL ? "SPECIAL_PRODUCT" : "APPLICABILITY_UNKNOWN",
                    true, "商品是否适用普通退货期限尚需人工核实");
        // Explicit duration contract: inclusive end, days * 24 hours. Never infer a legal rule.
        boolean within = !now.isAfter(receipt.signedAt().plus(Duration.ofDays(days)));
        return result(within ? "WITHIN_WINDOW" : "OUTSIDE_WINDOW", !within,
                within ? "符合已配置的退货期限；仍需其他业务校验及人工审核" : "超过已配置的普通退货期限；其他售后诉求请人工核实");
    }
    private Decision result(String status, boolean manual, String reason) { return new Decision(status, manual, reason); }
}
