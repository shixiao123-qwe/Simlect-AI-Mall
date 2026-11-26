package com.simlect.biz.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.simlect.component.RedisComponent;
import com.simlect.entity.config.AppConfig;
import com.simlect.api.dto.PayInfoDTO;
import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.api.enums.PayChannelEnum;
import com.simlect.exception.BusinessException;
import com.simlect.biz.PayChannel;
import com.simlect.utils.DateUtil;
import com.simlect.utils.OrderPayAmountUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.Map;

@Service("payChannel4Alipay")
public class PayChannel4AliPay implements PayChannel {

    private static final Logger log = LoggerFactory.getLogger(PayChannel4AliPay.class);

    static {
        // 设置SSL协议版本
        System.setProperty("https.protocols", "TLSv1.2");
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2");
    }

    private static final String TRADE_STATE_SUCCESS = "TRADE_SUCCESS";

    private static final String TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";

    private static final String NOTIFY_URL = "/api/notify/alipayNotify";
    private static final String RETURN_URL_PATH = "/payment/";

    @Resource
    private AppConfig appConfig;
    @Resource
    private RedisComponent redisComponent;

    @Value("${simlect.pay.local-mock-enabled:false}")
    private boolean localMockEnabled;

    @Override
    public PayInfoDTO getPayUrl(PayChannelEnum payChannelEnum, String payOrderId, String subject, BigDecimal amount) {
        if (localMockEnabled) {
            BigDecimal payAmount = OrderPayAmountUtil.normalizeChannelPayAmount(amount);
            log.info("本地支付占位模式：跳过支付宝下单 payOrderId={}, scene={}, amount={}",
                    payOrderId, payChannelEnum, payAmount);
            return new PayInfoDTO(null, payOrderId, payAmount);
        }
        try {
            BigDecimal payAmount = OrderPayAmountUtil.normalizeChannelPayAmount(amount);
            String payAmountText = OrderPayAmountUtil.formatChannelPayAmount(amount);
            // 初始化SDK
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());
            // 构造请求参数以调用接口
            AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            // 设置商户订单号
            model.setOutTradeNo(payOrderId);
            // 设置订单总金额
            model.setTotalAmount(payAmountText);
            // 设置订单标题
            model.setSubject(subject);
            //订单绝对超时时间
            model.setTimeExpire(DateUtil.getMinAfter(appConfig.getOrderExpireMinute(), DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()));

            request.setBizModel(model);

            //实际开发会设置异步通知回调信息，以便实时获取订单支付结果
            request.setNotifyUrl(appConfig.getProjectDomain() + NOTIFY_URL);
            request.setReturnUrl(appConfig.getProjectDomain() + RETURN_URL_PATH + payOrderId);
            String payInfo = null;
            switch (payChannelEnum) {
                case ALIPAY_PC -> {
                    model.setProductCode("FAST_INSTANT_TRADE_PAY");
                    AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
                    if (!response.isSuccess()) {
                        throw new BusinessException("获取支付信息失败");
                    }
                    payInfo = response.getBody();
                    redisComponent.markPayTradeInitiated(payOrderId);
                }
                case ALIPAY_WAP -> {
                    AlipayTradeWapPayRequest wapRequest = new AlipayTradeWapPayRequest();
                    AlipayTradeWapPayModel wapModel = new AlipayTradeWapPayModel();
                    wapModel.setOutTradeNo(payOrderId);
                    wapModel.setTotalAmount(payAmountText);
                    wapModel.setSubject(subject);
                    wapModel.setProductCode("QUICK_WAP_WAY");
                    wapModel.setTimeExpire(DateUtil.getMinAfter(appConfig.getOrderExpireMinute(),
                            DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()));
                    wapRequest.setBizModel(wapModel);
                    wapRequest.setNotifyUrl(appConfig.getProjectDomain() + NOTIFY_URL);
                    wapRequest.setReturnUrl(appConfig.getProjectDomain() + RETURN_URL_PATH + payOrderId);
                    AlipayTradeWapPayResponse wapResponse = alipayClient.pageExecute(wapRequest);
                    if (!wapResponse.isSuccess()) {
                        throw new BusinessException("获取支付信息失败");
                    }
                    payInfo = wapResponse.getBody();
                    redisComponent.markPayTradeInitiated(payOrderId);
                }
                default -> throw new BusinessException("不支持的支付方式");
            }
            return new PayInfoDTO(payInfo, payOrderId, payAmount);
        } catch (BusinessException e) {
            throw e;
        }catch (Exception e){
            log.error("支付宝获取支付信息失败");
            throw new BusinessException("获取支付信息失败");
        }
    }

    private AlipayConfig getAlipayConfig() {
        AlipayConfig config = new AlipayConfig();
        //支付宝网关
        config.setServerUrl(appConfig.getAlipayServerUrl());
        //应用ID
        config.setAppId(appConfig.getAlipayAppid());
        //应用私钥信息
        config.setPrivateKey(appConfig.getAlipayAppPrivateKey());
        //应用公钥证书本地地址
        config.setAppCertPath(appConfig.getProjectFolder() + appConfig.getAlipayAppCertPath());
        //支付宝公钥证书 alipayPublicCert.crt
        config.setAlipayPublicCertPath(appConfig.getProjectFolder() + appConfig.getAlipayPublicCertPath());
        //支付宝根证书本地地址
        config.setRootCertPath(appConfig.getProjectFolder() + appConfig.getAlipayRootCertPath());
        config.setCharset("UTF8");
        config.setSignType("RSA2");
        config.setFormat("json");
        // 设置超时时间（毫秒）
        config.setConnectTimeout(30000);  // 连接超时30秒
        config.setReadTimeout(30000);     // 读取超时30秒
        return config;
    }

    @Override
    public PayOrderNotifyDTO payNotify(Map<String, String> requestParams, String jsonBody) {
        try {
            if (requestParams == null || !"RSA2".equals(requestParams.get("sign_type"))
                    || requestParams.get("sign") == null || requestParams.get("sign").isBlank())
                throw new BusinessException("支付宝回调签名参数无效");
            requestParams.remove("sign_type");
            Boolean signCheckResult = AlipaySignature.rsaCertCheckV2(requestParams, appConfig.getProjectFolder() + appConfig.getAlipayPublicCertPath(), "UTF-8", "RSA2");
            if (!Boolean.TRUE.equals(signCheckResult)) {
                throw new BusinessException("支付宝回调校验失败");
            }
        } catch (AlipayApiException e) {
            log.error("支付宝回调检验失败", e);
            throw new BusinessException("支付宝回调校验失败");
        }
        // Signed external form: validate all identities and amounts before normalizing the DTO.
        String payOrderId = requiredField(requestParams, "out_trade_no", "[A-Za-z0-9_-]{1,64}");
        String channelOrderId = requiredField(requestParams, "trade_no", "[0-9]{1,64}");
        String appId = requiredField(requestParams, "app_id", "[0-9]{1,32}");
        String sellerId = requiredField(requestParams, "seller_id", "[0-9]{1,32}");
        if (appConfig.getAlipayAppid() == null || appConfig.getAlipayAppid().isBlank()
                || appConfig.getAlipaySellerId() == null || appConfig.getAlipaySellerId().isBlank()
                || !appId.equals(appConfig.getAlipayAppid()) || !sellerId.equals(appConfig.getAlipaySellerId())) {
            throw new BusinessException("支付宝回调商户身份不匹配或未配置");
        }
        BigDecimal amount = parsePaymentAmount(requestParams.get("total_amount"));
        String status = requestParams.get("trade_status");
        if (!java.util.Set.of("TRADE_SUCCESS", "TRADE_FINISHED", "WAIT_BUYER_PAY", "TRADE_CLOSED").contains(status == null ? "" : status)) {
            throw new BusinessException("支付宝交易状态无效");
        }
        if (!isPaid(status)) return null;
        return new PayOrderNotifyDTO(payOrderId, channelOrderId, amount);
    }

    private String requiredField(Map<String,String> params, String name, String pattern) {
        String value = params.get(name);
        if (value == null || !value.matches(pattern)) throw new BusinessException("支付宝字段无效: " + name);
        return value;
    }

    private boolean isPaid(String status) {
        return TRADE_STATE_SUCCESS.equals(status) || "TRADE_FINISHED".equals(status);
    }

    private BigDecimal parsePaymentAmount(String text) {
        // No whitespace, signs, exponent notation, separators or sub-cent rounding.
        if (text == null || text.length() > 32 || !text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?"))
            throw new BusinessException("支付宝支付金额格式无效");
        BigDecimal amount = new BigDecimal(text);
        if (amount.signum() <= 0) throw new BusinessException("支付宝支付金额必须大于零");
        return amount;
    }

    @Override
    public PayOrderNotifyDTO queryOrder(String payOrderId) {
        if (localMockEnabled) {
            log.info("本地支付联调模式跳过支付宝查单 payOrderId={}", payOrderId);
            return null;
        }
        try {
            // 初始化SDK
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            // 设置商户订单号
            model.setOutTradeNo(payOrderId);
            request.setBizModel(model);
            AlipayTradeQueryResponse response = alipayClient.certificateExecute(request);

            if (!response.isSuccess()) {
                String subCode = response.getSubCode();
                if (TRADE_NOT_EXIST.equals(subCode)) {
                    return null;
                }
                log.debug("支付宝查单未成功 payOrderId={}, subCode={}, subMsg={}",
                        payOrderId, subCode, response.getSubMsg());
                return null;
            }
            if (!isPaid(response.getTradeStatus())) {
                return null;
            }
            log.info("查询支付宝订单已支付 payOrderId={}, tradeNo={}", payOrderId, response.getTradeNo());
            // SDK certificateExecute verifies the response signature. Query results do not
            // contain the external callback's app_id/seller_id/sign_type form contract.
            if (payOrderId == null || !payOrderId.equals(response.getOutTradeNo())
                    || response.getTradeNo() == null || !response.getTradeNo().matches("[0-9]{1,64}"))
                throw new BusinessException("支付宝查单结果与支付订单不匹配");
            return new PayOrderNotifyDTO(payOrderId, response.getTradeNo(), parsePaymentAmount(response.getTotalAmount()));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("支付宝获取支付信息失败", e);
            throw new BusinessException("获取支付信息失败");
        }
    }

    @Override
    public void refund(String sourcePayOrderId, String payOrderId, BigDecimal refundAmount) {
        if (localMockEnabled) {
            log.info("本地支付联调模式跳过支付宝退款 sourcePayOrderId={}, refundOrderId={}",
                    sourcePayOrderId, payOrderId);
            return;
        }
        // 退款幂等闸：以 out_request_no（order 侧稳定的 refundOrderId）为幂等键；
        // 成功保留（防重放二次退款），失败清理（允许重试）
        String refundIdempotencyKey = "mall:pay:refund:req:" + payOrderId;
        if (!redisComponent.setIfAbsent(
                refundIdempotencyKey, "1",
                7 * 24 * 3600L, TimeUnit.SECONDS)) {
            log.info("退款请求已处理过（幂等跳过） refundOrderId={}", payOrderId);
            return;
        }
        try {
            // 初始化SDK
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            // 设置商户订单号
            model.setOutTradeNo(sourcePayOrderId);
            model.setOutRequestNo(payOrderId);
            model.setRefundAmount(refundAmount.toString());
            request.setBizModel(model);
            AlipayTradeRefundResponse response = alipayClient.certificateExecute(request);
            log.info("支付宝退款:{},返回结果:{}", payOrderId, response.getBody());
            if (!response.isSuccess()) {
                throw new BusinessException("退款失败");
            }
        } catch (BusinessException e) {
            // 退款失败：清理幂等闸，允许同一退款单号重试
            redisComponent.deleteCacheKey(refundIdempotencyKey);
            throw e;
        } catch (Exception e) {
            log.error("支付宝退款失败", e);
            redisComponent.deleteCacheKey(refundIdempotencyKey);
            throw new BusinessException("支付宝退款失败");
        }
    }

    @Override
    public void closeOrder(String payOrderId) {
        if (localMockEnabled) {
            log.info("本地支付联调模式跳过支付宝关单 payOrderId={}", payOrderId);
            return;
        }
        try {
            AlipayClient alipayClient = new DefaultAlipayClient(getAlipayConfig());
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            // 设置商户订单号
            model.setOutTradeNo(payOrderId);
            request.setBizModel(model);
            AlipayTradeCloseResponse response = alipayClient.certificateExecute(request);
            if (!response.isSuccess()) {
                String subCode = response.getSubCode();
                // 忽略交易不存在和系统繁忙的错误
                if (!TRADE_NOT_EXIST.equals(subCode) && !"aop.unknow-error".equals(subCode)) {
                    throw new BusinessException("订单关闭失败");
                }
                log.warn("支付宝订单关闭返回非成功状态: subCode={}, payOrderId={}", subCode, payOrderId);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("支付宝订单关闭失败, payOrderId={}", payOrderId, e);
        }
    }
}
