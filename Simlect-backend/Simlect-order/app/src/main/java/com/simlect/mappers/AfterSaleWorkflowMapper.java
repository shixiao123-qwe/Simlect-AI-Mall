package com.simlect.mappers;
import org.apache.ibatis.annotations.*;
import java.util.*;
import java.math.BigDecimal;

public interface AfterSaleWorkflowMapper {
    String FIELDS="r.request_id AS requestId,r.user_id AS userId,r.order_id AS orderId,r.order_item_id AS orderItemId,r.request_type AS type,r.reason,r.status,r.policy_version AS policyVersion,r.created_at AS createdAt,COALESCE(f.phase,r.status) AS phase,f.review_note AS reviewNote,f.return_address AS returnAddress,f.return_carrier AS returnCarrier,f.return_tracking AS returnTracking,f.inspection_note AS inspectionNote,f.restockable,f.refund_amount AS refundAmount,f.refund_reference AS refundReference,f.simulated,i.product_name AS productName";
    String JOINED=" FROM after_sale_request r LEFT JOIN after_sale_fulfillment f ON f.request_id=r.request_id COLLATE utf8mb4_general_ci LEFT JOIN order_item i ON i.order_item_id=r.order_item_id COLLATE utf8mb4_general_ci ";
    @Select("SELECT logistics_status FROM order_logistics_info WHERE order_id=#{id}") Integer logisticsStatus(@Param("id") String id);
    @Select("SELECT "+FIELDS+JOINED+" WHERE r.request_id=#{id}") Map<String,Object> detail(@Param("id") String id);
    @Select("SELECT request_id FROM after_sale_request WHERE request_id=#{id} FOR UPDATE") String lockRequest(@Param("id") String id);
    @Insert("INSERT IGNORE INTO after_sale_fulfillment(request_id) VALUES(#{id})") int ensure(@Param("id") String id);
    @Select("SELECT "+FIELDS+JOINED+" WHERE r.user_id=#{user} ORDER BY r.created_at DESC LIMIT 50") List<Map<String,Object>> mine(@Param("user") String user);
    @Select("SELECT "+FIELDS+JOINED+" WHERE (#{status} IS NULL OR r.status=#{status}) ORDER BY r.created_at DESC LIMIT 50 OFFSET #{offset}") List<Map<String,Object>> list(@Param("status") String status,@Param("offset") int offset);
    @Update("UPDATE after_sale_request SET status=#{target} WHERE request_id=#{id} AND status=#{expected}") int status(@Param("id") String id,@Param("expected") String expected,@Param("target") String target);
    @Update("UPDATE after_sale_fulfillment SET phase=#{target} WHERE request_id=#{id} AND phase=#{expected}") int phase(@Param("id") String id,@Param("expected") String expected,@Param("target") String target);
    @Update("UPDATE after_sale_fulfillment SET review_note=#{note},return_address=#{address} WHERE request_id=#{id}") int review(@Param("id") String id,@Param("note") String note,@Param("address") String address);
    @Update("UPDATE after_sale_fulfillment SET return_carrier=#{carrier},return_tracking=#{tracking} WHERE request_id=#{id}") int tracking(@Param("id") String id,@Param("carrier") String carrier,@Param("tracking") String tracking);
    @Update("UPDATE after_sale_fulfillment SET inspection_note=#{note},restockable=#{restock} WHERE request_id=#{id}") int inspection(@Param("id") String id,@Param("note") String note,@Param("restock") boolean restock);
    @Update("UPDATE after_sale_fulfillment SET refund_reference=#{reference} WHERE request_id=#{id} AND (refund_reference IS NULL OR refund_reference=#{reference})") int bindReference(@Param("id") String id,@Param("reference") String reference);
    @Update("UPDATE after_sale_fulfillment SET refund_amount=#{amount},refund_reference=#{reference},simulated=#{simulated} WHERE request_id=#{id}") int result(@Param("id") String id,@Param("amount") BigDecimal amount,@Param("reference") String reference,@Param("simulated") boolean simulated);
    @Insert("INSERT INTO after_sale_log(request_id,from_status,to_status,actor_id,remark) VALUES(#{id},#{from},#{to},#{actor},#{note})") int log(@Param("id") String id,@Param("from") String from,@Param("to") String to,@Param("actor") String actor,@Param("note") String note);
    @Select("SELECT from_status AS fromStatus,to_status AS toStatus,remark,created_at AS createdAt FROM after_sale_log WHERE request_id=#{id} ORDER BY log_id LIMIT 200") List<Map<String,Object>> logs(@Param("id") String id);
}
