package com.simlect.inbox;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InboxMapperContractTest {
    Configuration configuration() throws Exception {
        String resource="com/simlect/mappers/MqInboxMapper.xml";var configuration=new Configuration();
        try(var reader=Resources.getResourceAsReader(resource)){new XMLMapperBuilder(reader,configuration,resource,configuration.getSqlFragments()).parse();}
        return configuration;
    }
    String sql(String method) throws Exception {
        return configuration().getMappedStatement("com.simlect.mappers.MqInboxMapper."+method)
                .getBoundSql(Map.of("consumer","queue","eventId","event","eventType","ORDER","error","failure")).getSql().replaceAll("\\s+"," ");
    }
    @Test void currentReadAndConsumerNamespaceAreMandatory() throws Exception {String sql=sql("lock");assertTrue(sql.contains("consumer_name=? AND event_id=? FOR UPDATE"));}
    @Test void doneOnlyUpdatesProcessing() throws Exception {assertTrue(sql("complete").contains("AND status='PROCESSING'"));assertTrue(sql("complete").contains("completed_time=now(3)"));}
    @Test void failureNeverDowngradesDone() throws Exception {assertTrue(sql("fail").contains("AND status='FAILED' AND dlq_enqueued=0"));}
    @Test void insertDoesNotReplaceExistingCompletion() throws Exception {assertTrue(sql("insertProcessing").contains("INSERT IGNORE"));assertFalse(sql("insertProcessing").contains("REPLACE"));}
}
