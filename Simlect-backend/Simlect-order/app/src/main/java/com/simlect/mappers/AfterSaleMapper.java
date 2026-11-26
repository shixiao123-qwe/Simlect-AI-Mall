package com.simlect.mappers;

import com.simlect.entity.po.OrderInfo;
import com.simlect.entity.po.OrderItem;
import org.apache.ibatis.annotations.*;
import java.util.Map;
import java.util.List;

public interface AfterSaleMapper {
    @Select("SELECT * FROM order_info WHERE order_id=#{id} FOR UPDATE")
    @Results({@Result(column="order_id",property="orderId"), @Result(column="user_id",property="userId"), @Result(column="order_status",property="orderStatus")})
    OrderInfo lockOrder(@Param("id") String id);
    @Select("SELECT * FROM order_item WHERE order_item_id=#{id} FOR UPDATE")
    @Results({@Result(column="order_item_id",property="orderItemId"), @Result(column="order_id",property="orderId"), @Result(column="order_item_status",property="orderItemStatus")})
    OrderItem lockItem(@Param("id") String id);
    @Select("SELECT request_id AS requestId, user_id AS userId, order_item_id AS orderItemId, request_type AS type, reason, status FROM after_sale_request WHERE request_id=#{id}")
    Map<String,Object> byId(@Param("id") String id);
    @Select("SELECT request_id AS requestId FROM after_sale_request WHERE active_item_id=#{id} LIMIT 1")
    Map<String,Object> active(@Param("id") String id);
    @Select("SELECT request_id AS requestId FROM after_sale_request WHERE active_item_id=#{id} LIMIT 1 FOR UPDATE")
    Map<String,Object> activeForUpdate(@Param("id") String id);
    @Insert("INSERT INTO after_sale_request(request_id,user_id,order_id,order_item_id,request_type,reason,status,policy_version) VALUES(#{requestId},#{userId},#{orderId},#{orderItemId},#{type},#{reason},'PENDING_REVIEW',#{policyVersion})")
    int insert(Map<String,Object> row);
    @Insert("INSERT INTO after_sale_log(request_id,from_status,to_status,actor_id,remark) VALUES(#{requestId},'INIT','PENDING_REVIEW',#{userId},'用户确认提交；未执行退款')")
    int insertLog(Map<String,Object> row);
    @Select("SELECT request_id AS requestId,order_item_id AS orderItemId,request_type AS type,status,created_at AS createdAt FROM after_sale_request WHERE user_id=#{userId} ORDER BY created_at DESC LIMIT 30")
    List<Map<String,Object>> mine(@Param("userId") String userId);
}
