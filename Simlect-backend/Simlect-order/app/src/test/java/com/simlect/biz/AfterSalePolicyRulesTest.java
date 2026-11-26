package com.simlect.biz;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.simlect.biz.AfterSalePolicyRules.*;

class AfterSalePolicyRulesTest {
    final Instant signed=Instant.parse("2026-01-01T00:00:00Z");
    AfterSalePolicyRules configured() {
        var rules=new AfterSalePolicyRules();
        ReflectionTestUtils.setField(rules,"days",7);
        ReflectionTestUtils.setField(rules,"source","test-fixture-policy-only");
        return rules;
    }
    Decision evaluate(AfterSalePolicyRules rules, Instant now) {
        return rules.evaluate("return_refund",new Receipt(signed,true),Applicability.STANDARD,now);
    }
    @Test void beforeDeadline() { assertEquals("WITHIN_WINDOW",evaluate(configured(),signed.plusSeconds(604799)).status()); }
    @Test void exactlySevenDaysIncluded() { assertEquals("WITHIN_WINDOW",evaluate(configured(),signed.plusSeconds(604800)).status()); }
    @Test void oneNanosecondOutside() { assertEquals("OUTSIDE_WINDOW",evaluate(configured(),signed.plusSeconds(604800).plusNanos(1)).status()); }
    @Test void missingPolicyNotApproved() { assertEquals("POLICY_MISSING",evaluate(new AfterSalePolicyRules(),signed).status()); }
    @Test void missingSourceNotApproved() { var r=configured();ReflectionTestUtils.setField(r,"source","");assertEquals("POLICY_MISSING",evaluate(r,signed).status()); }
    @Test void invalidDaysNotApproved() { var r=configured();ReflectionTestUtils.setField(r,"days",-1);assertEquals("POLICY_MISSING",evaluate(r,signed).status()); }
    @Test void excessiveDaysNotApproved() { var r=configured();ReflectionTestUtils.setField(r,"days",366);assertEquals("POLICY_MISSING",evaluate(r,signed).status()); }
    @Test void missingReceiptManual() { assertTrue(configured().evaluate("return_refund",Receipt.unavailable(),Applicability.STANDARD,signed).manualReviewRequired()); }
    @Test void unverifiedReceiptNotUsed() { assertEquals("RECEIPT_MISSING",configured().evaluate("return_refund",new Receipt(signed,false),Applicability.STANDARD,signed).status()); }
    @Test void futureReceiptNotUsed() { assertEquals("RECEIPT_INVALID",evaluate(configured(),signed.minusSeconds(1)).status()); }
    @Test void specialProductsNotAssumedStandard() { assertEquals("SPECIAL_PRODUCT",configured().evaluate("return_refund",new Receipt(signed,true),Applicability.SPECIAL,signed).status()); }
    @Test void missingApplicabilityNotAssumedStandard() { assertEquals("APPLICABILITY_UNKNOWN",configured().evaluate("return_refund",new Receipt(signed,true),Applicability.UNKNOWN,signed).status()); }
    @Test void unshippedRefundNotSubjectToReceiptWindow() { assertEquals("NOT_APPLICABLE",new AfterSalePolicyRules().evaluate("refund",null,null,signed).status()); }
    @Test void sixDaysRemainWithinWindow() { assertEquals("WITHIN_WINDOW",evaluate(configured(),signed.plus(Duration.ofDays(6))).status()); }
}
