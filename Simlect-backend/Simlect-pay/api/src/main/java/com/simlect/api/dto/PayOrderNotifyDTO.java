package com.simlect.api.dto;

import java.math.BigDecimal;

/** Normalized successful payment result, not a binding model for the external signed form.
 * Callback parsing and SDK query parsing have separate validation boundaries; both must
 * supply an amount before order-side processing. No query-source flag can bypass validation.
 */
public class PayOrderNotifyDTO {

    private String payOrderId;

    private String channelOrderId;

    /** 支付渠道回调的实付金额（支付宝 total_amount），用于回调金额校验 */
    private BigDecimal totalAmount;

    private PayOrderNotifyDTO() {
    }

    public PayOrderNotifyDTO(String payOrderId, String channelOrderId) {
        this.payOrderId = payOrderId;
        this.channelOrderId = channelOrderId;
    }

    public PayOrderNotifyDTO(String payOrderId, String channelOrderId, BigDecimal totalAmount) {
        this(payOrderId, channelOrderId);
        this.totalAmount = totalAmount;
    }

    public String getPayOrderId() {
        return payOrderId;
    }

    public void setPayOrderId(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public String getChannelOrderId() {
        return channelOrderId;
    }

    public void setChannelOrderId(String channelOrderId) {
        this.channelOrderId = channelOrderId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
}
