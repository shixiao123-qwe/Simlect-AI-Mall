package com.simlect.service.impl;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OutboxMapperContractTest {
    Configuration configuration() throws Exception {
        var config = new Configuration();
        String resource = "com/simlect/mappers/LocalMessageOutboxMapper.xml";
        try (var reader = Resources.getResourceAsReader(resource)) {
            new XMLMapperBuilder(reader, config, resource, config.getSqlFragments()).parse();
        }
        return config;
    }
    String sql(String method, Map<String,Object> params) throws Exception {
        return configuration().getMappedStatement("com.simlect.mappers.LocalMessageOutboxMapper." + method)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ");
    }
    @Test void claimIsAtomicAndIncludesExpiredLeaseBackoffAndBudget() throws Exception {
        String sql = sql("claimLease", Map.of("id", 1L, "owner", "claim", "leaseSeconds", 60,
                "maxAttempts", 10, "backoffBaseSeconds", 2, "backoffMaxSeconds", 300));
        assertTrue(sql.contains("WHERE id = ? AND"));
        assertTrue(sql.contains("ifnull(retry_count, 0) < ?"));
        assertTrue(sql.contains("lease_until <= now(3)"));
        assertTrue(sql.contains("lease_until IS NULL"));
        assertTrue(sql.contains("next_attempt_time <= now(3)"));
        assertTrue(sql.contains("retry_count = ifnull(retry_count, 0) + 1"));
    }
    @Test void bothResultsAreFencedByOwnerStateAndDatabaseExpiry() throws Exception {
        for (boolean sent : new boolean[]{true, false}) {
            String sql = sql("completeLease", Map.of("id", 1L, "owner", "claim", "sent", sent,
                    "error", "error", "backoffBaseSeconds", 2, "backoffMaxSeconds", 300));
            assertTrue(sql.contains("WHERE id = ? AND status = 1 AND lease_owner = ? AND lease_until > now(3)"));
            assertTrue(sql.contains("SET status = " + (sent ? "2" : "3")));
        }
    }
    @Test void retryFilterPrecedesLimitSoExhaustedRowsCannotStarveNewMessages() throws Exception {
        String sql = sql("selectReadyBatch", Map.of("limit", 30, "maxAttempts", 10, "leaseSeconds", 60));
        assertTrue(sql.indexOf("retry_count") < sql.indexOf("LIMIT"));
        assertTrue(sql.contains("status IN (0, 3) OR (status = 1"));
    }
    @Test void exhaustionOnlyFinalizesExpiredSendingAndDoesNotResetToPending() throws Exception {
        String sql = sql("expireExhausted", Map.of("limit", 30, "maxAttempts", 10, "leaseSeconds", 60));
        assertTrue(sql.contains("SET status = 3"));
        assertTrue(sql.contains("WHERE status = 1 AND ifnull(retry_count, 0) >= ?"));
        assertTrue(sql.contains("lease_until <= now(3)"));
    }
    @Test void legacyStatusMethodCannotBypassLeaseFencing() throws Exception {
        String sql = sql("updateStatus", Map.of("id",1L,"fromStatus",1,"toStatus",2,"incRetry",false));
        assertTrue(sql.contains("status in (0, 3)"));
        assertTrue(sql.contains("and ? in (0, 3)"));
        assertTrue(sql.contains("lease_owner is null"));
    }
}
