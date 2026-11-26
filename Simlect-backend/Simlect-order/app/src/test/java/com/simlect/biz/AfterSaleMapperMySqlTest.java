package com.simlect.biz;

import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.po.*;
import com.simlect.mappers.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real mapper/SQL contract. One JDBC transaction, all fixture rows rolled back. */
@EnabledIfEnvironmentVariable(named="SIMLECT_AFTER_SALE_DB_TEST",matches="true")
class AfterSaleMapperMySqlTest {
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void realMapperCreatesRequestAndAuditAndReplays() throws Exception {
        String password=System.getenv("MYSQL_PASSWORD");
        assertNotNull(password,"Explicit local test credential required");
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/simlect_order?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv().getOrDefault("MYSQL_USER","root"),password);
        Configuration cfg=new Configuration(new Environment("local-rollback",new JdbcTransactionFactory(),ds));
        cfg.addMapper(AfterSaleMapper.class);
        SqlSessionFactory factory=new SqlSessionFactoryBuilder().build(cfg);
        String orderId=UUID.randomUUID().toString().replace("-",""); String itemId=orderId+"_1";
        try(SqlSession session=factory.openSession(false)) {
            try {
                var connection=session.getConnection();
                try(var ps=connection.prepareStatement("INSERT INTO order_info(order_id,user_id,order_status) VALUES(?,'AS_TEST_USER',1)")) {
                    ps.setString(1,orderId);ps.executeUpdate();
                }
                try(var ps=connection.prepareStatement("INSERT INTO order_item(order_item_id,order_id,product_id,product_name,property_value_id_hash,order_item_status) VALUES(?,?,'AS_TEST_PRODUCT','rollback fixture','test',1)")) {
                    ps.setString(1,itemId);ps.setString(2,orderId);ps.executeUpdate();
                }
                AfterSaleMapper mapper=session.getMapper(AfterSaleMapper.class);
                OrderInfo order=mapper.lockOrder(orderId); OrderItem item=mapper.lockItem(itemId);
                assertEquals("AS_TEST_USER",order.getUserId()); assertEquals(1,order.getOrderStatus());
                assertEquals(itemId,item.getOrderItemId()); assertEquals(1,item.getOrderItemStatus());
                OrderInfoMapper orders=mock(OrderInfoMapper.class); OrderItemMapper items=mock(OrderItemMapper.class);
                when(orders.selectByOrderId(orderId)).thenReturn(order);when(items.selectByOrderItemId(itemId)).thenReturn(item);
                AfterSaleService service=new AfterSaleService(orders,items,mapper);
                var dto=new AfterSaleDTO.Create(UUID.randomUUID().toString().replace("-",""),itemId,"refund","测试，事务回滚");
                assertEquals("PENDING_REVIEW",service.create("AS_TEST_USER",dto).get("status"));
                assertEquals(dto.requestId(),mapper.active(itemId).get("requestId"));
                assertEquals(dto.requestId(),mapper.activeForUpdate(itemId).get("requestId"));
                assertEquals(dto.requestId(),service.create("AS_TEST_USER",dto).get("requestId"));
                try(var ps=connection.prepareStatement("SELECT COUNT(*) FROM after_sale_log WHERE request_id=?")) {
                    ps.setString(1,dto.requestId());try(var rs=ps.executeQuery()) { rs.next();assertEquals(1,rs.getInt(1)); }
                }
                assertThrows(com.simlect.exception.BusinessException.class,()->service.create("other",dto));
                // Unique active-item index is the final concurrent-application guard.
                try(var ps=connection.prepareStatement("INSERT INTO after_sale_request(request_id,user_id,order_id,order_item_id,request_type,reason,status,policy_version) VALUES(?,'AS_TEST_USER',?,?,'refund','test','PENDING_REVIEW','demo-v1')")) {
                    ps.setString(1,UUID.randomUUID().toString().replace("-",""));ps.setString(2,orderId);ps.setString(3,itemId);
                    assertThrows(java.sql.SQLException.class,ps::executeUpdate);
                }
            } finally { session.rollback(true); }
        }
        try(var connection=ds.getConnection();var ps=connection.prepareStatement("SELECT COUNT(*) FROM order_info WHERE order_id=?")) {
            ps.setString(1,orderId);try(var rs=ps.executeQuery()) { rs.next();assertEquals(0,rs.getInt(1)); }
        }
    }
}
