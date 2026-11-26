package com.simlect.biz.impl;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.entity.config.AppConfig;
import com.simlect.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All signatures, certificates, merchant IDs and SDK responses are test doubles. */
class PayChannel4AliPaySecurityTest {
    static final String PAY="PO20260815001", TRADE="202608152200100001";
    PayChannel4AliPay channel; AppConfig config;
    @BeforeEach void setup() {
        channel=new PayChannel4AliPay();config=mock(AppConfig.class);
        ReflectionTestUtils.setField(channel,"appConfig",config);
        when(config.getProjectFolder()).thenReturn("mock/");
        when(config.getAlipayPublicCertPath()).thenReturn("cert");
        when(config.getAlipayAppid()).thenReturn("20210001");
        when(config.getAlipaySellerId()).thenReturn("2088000000000001");
    }
    Map<String,String> params() {
        return new HashMap<>(Map.of("out_trade_no",PAY,"trade_no",TRADE,"trade_status","TRADE_SUCCESS",
                "total_amount","100.00","app_id","20210001","seller_id","2088000000000001",
                "sign_type","RSA2","sign","mock-signature"));
    }
    PayOrderNotifyDTO verified(Map<String,String> params) {
        try(var signature=mockStatic(AlipaySignature.class)) {
            signature.when(()->AlipaySignature.rsaCertCheckV2(anyMap(),anyString(),eq("UTF-8"),eq("RSA2"))).thenReturn(true);
            return channel.payNotify(params,null);
        }
    }
    @ParameterizedTest @ValueSource(strings={"out_trade_no","trade_no","trade_status","total_amount","app_id","seller_id"})
    void missingRequiredFieldNeverCreatesSuccessfulResult(String name) {
        var params=params();params.remove(name);
        assertThrows(BusinessException.class,()->verified(params));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={""," ","NaN","1e2","+100.00","-1.00","100.001","100.000","100.",".50","01.00","0","0.00","1,000.00"," 100.00","100.00 "})
    void invalidOrMissingAmountIsRejected(String amount) {
        var params=params();params.put("total_amount",amount);
        assertThrows(BusinessException.class,()->verified(params));
    }
    @ParameterizedTest @ValueSource(strings={"100","100.0","100.00","0.01"})
    void exactDecimalAmountsArePreserved(String amount) {
        var params=params();params.put("total_amount",amount);
        assertEquals(0,new BigDecimal(amount).compareTo(verified(params).getTotalAmount()));
    }
    @ParameterizedTest @ValueSource(strings={"app_id","seller_id"})
    void wrongMerchantIsRejectedAfterSignatureValidation(String name) {
        var params=params();params.put(name,"99999999");
        assertThrows(BusinessException.class,()->verified(params));
    }
    @ParameterizedTest @ValueSource(strings={"app_id","seller_id"})
    void blankMerchantConfigurationFailsClosed(String name) {
        if(name.equals("app_id"))when(config.getAlipayAppid()).thenReturn("");
        else when(config.getAlipaySellerId()).thenReturn("");
        assertThrows(BusinessException.class,()->verified(params()));
    }
    @ParameterizedTest @CsvSource({"out_trade_no,bad order","trade_no,not-a-trade","app_id,bad-app","seller_id,bad-seller","trade_status,trade_success","trade_status,UNKNOWN"})
    void malformedIdentityOrStatusIsRejected(String field,String value) {
        var params=params();params.put(field,value);
        assertThrows(BusinessException.class,()->verified(params));
    }
    @Test void finishedTradeStillCarriesStrictVerifiedAmount() {
        var params=params();params.put("trade_status","TRADE_FINISHED");
        assertEquals(new BigDecimal("100.00"),verified(params).getTotalAmount());
    }
    @Test void wrongMerchantCannotBeIgnoredAsHarmlessUnpaidNotification() {
        var params=params();params.put("trade_status","WAIT_BUYER_PAY");params.put("app_id","999");
        assertThrows(BusinessException.class,()->verified(params));
    }
    @Test void missingSignatureNeverInvokesVerifier() {
        var params=params();params.remove("sign");
        try(var signature=mockStatic(AlipaySignature.class)) {
            assertThrows(BusinessException.class,()->channel.payNotify(params,null));signature.verifyNoInteractions();
        }
    }
    @Test void unsupportedSignatureTypeNeverInvokesVerifier() {
        var params=params();params.put("sign_type","RSA");
        try(var signature=mockStatic(AlipaySignature.class)) {
            assertThrows(BusinessException.class,()->channel.payNotify(params,null));signature.verifyNoInteractions();
        }
    }
    PayOrderNotifyDTO query(String outTradeNo,String tradeNo,String amount) throws Exception {
        AlipayTradeQueryResponse response=mock(AlipayTradeQueryResponse.class);
        when(response.isSuccess()).thenReturn(true);when(response.getTradeStatus()).thenReturn("TRADE_SUCCESS");
        when(response.getOutTradeNo()).thenReturn(outTradeNo);when(response.getTradeNo()).thenReturn(tradeNo);
        when(response.getTotalAmount()).thenReturn(amount);
        try(var sdk=mockConstruction(DefaultAlipayClient.class,(mock,context)->
                when(mock.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            PayOrderNotifyDTO result=channel.queryOrder(PAY);
            assertEquals(1,sdk.constructed().size());return result;
        }
    }
    @Test void internalQueryNeedsAmountButNotExternalCallbackFormFields() throws Exception {
        assertEquals(new BigDecimal("100.00"),query(PAY,TRADE,"100.00").getTotalAmount());
        verify(config,never()).getAlipaySellerId();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"","broken","100.001"})
    void queryCannotIntroduceAmountlessOrMalformedPayment(String amount) {
        assertThrows(BusinessException.class,()->query(PAY,TRADE,amount));
    }
    @Test void queryCannotAssociateAnotherOrdersPayment() {
        assertThrows(BusinessException.class,()->query("OTHER",TRADE,"100.00"));
    }
    @Test void queryCannotAcceptMissingChannelTrade() {
        assertThrows(BusinessException.class,()->query(PAY,null,"100.00"));
    }
}
