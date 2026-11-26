package com.simlect.biz;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RefundMapperContractTest {
    @Test
    void refundReadsAreCurrentLockingReadsAndMapAccumulatedRefundAmounts() throws Exception {
        String resource = "com/simlect/mappers/OrderInfoMapper.xml";
        Configuration configuration = new Configuration();
        try (var reader = Resources.getResourceAsReader(resource)) {
            new XMLMapperBuilder(reader, configuration, resource, configuration.getSqlFragments()).parse();
        }
        for (String method : new String[]{"selectForRefundByOrderId", "selectRefundLogisticsStatus"}) {
            var statement = configuration.getMappedStatement("com.simlect.mappers.OrderInfoMapper." + method);
            String sql = statement.getBoundSql(Map.of("orderId", "o1")).getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("FOR UPDATE"));
            assertTrue(sql.contains("order_id = ?"));
        }
        var mapping = configuration.getResultMap("com.simlect.mappers.OrderInfoMapper.base_result_map")
                .getResultMappings().stream().filter(field -> "orderItemList".equals(field.getProperty()))
                .findFirst().orElseThrow();
        assertTrue(configuration.getResultMap(mapping.getNestedResultMapId()).getResultMappings().stream()
                .anyMatch(field -> "refundAmount".equals(field.getProperty()) && "refund_amount".equals(field.getColumn())));
    }
}
