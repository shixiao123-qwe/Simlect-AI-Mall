package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface MqInboxMapper {
    int insertProcessing(@Param("consumer") String consumer, @Param("eventId") String eventId, @Param("eventType") String eventType);
    int insertFailed(@Param("consumer") String consumer, @Param("eventId") String eventId, @Param("eventType") String eventType);
    Map<String,Object> lock(@Param("consumer") String consumer, @Param("eventId") String eventId);
    int begin(@Param("consumer") String consumer, @Param("eventId") String eventId);
    int complete(@Param("consumer") String consumer, @Param("eventId") String eventId);
    int fail(@Param("consumer") String consumer, @Param("eventId") String eventId, @Param("error") String error);
    int markDeadLettered(@Param("consumer") String consumer, @Param("eventId") String eventId);
}
