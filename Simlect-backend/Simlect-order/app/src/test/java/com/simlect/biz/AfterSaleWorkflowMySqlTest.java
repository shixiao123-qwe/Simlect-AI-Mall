package com.simlect.biz;
import com.simlect.component.RedisComponent;
import com.simlect.entity.dto.AfterSaleDTO;
import com.simlect.entity.po.*;
import com.simlect.mappers.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentMatchers;
import java.util.*;
import java.util.concurrent.Callable;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL/state/audit transactions; payment and inventory boundaries mocked, all fixtures rolled back. */
@EnabledIfEnvironmentVariable(named="SIMLECT_AFTER_SALE_DB_TEST",matches="true")
class AfterSaleWorkflowMySqlTest {
    @ParameterizedTest @ValueSource(booleans={false,true}) void realWorkflow(boolean returning) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/simlect_order?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv().getOrDefault("MYSQL_USER","root"),System.getenv("MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);
        var cfg=new Configuration();cfg.addMapper(AfterSaleWorkflowMapper.class);cfg.addMapper(AfterSaleMapper.class);
        var fb=new SqlSessionFactoryBean();fb.setDataSource(ds);fb.setConfiguration(cfg);fb.setTransactionFactory(new SpringManagedTransactionFactory());
        var template=new SqlSessionTemplate(fb.getObject());
        var mapper=template.getMapper(AfterSaleWorkflowMapper.class);var application=template.getMapper(AfterSaleMapper.class);
        var orders=mock(OrderInfoService.class);var items=mock(OrderItemService.class);var redis=mock(RedisComponent.class);
        String id=UUID.randomUUID().toString().replace("-","");String oid=UUID.randomUUID().toString().replace("-","");String itemId=oid+"_1";
        var pay=mock(com.simlect.api.support.PayFeignSupport.class);
        var service=new AfterSaleWorkflowService(mapper,application,orders,items,redis,pay,manager);
        ReflectionTestUtils.setField(service,"mockEnabled",true);ReflectionTestUtils.setField(service,"refundEnabled",true);ReflectionTestUtils.setField(service,"returnAddress","演示仓库，不得真实寄件");
        when(orders.getOrderInfoByOrderId(oid)).thenAnswer(i->jdbc.queryForObject("SELECT * FROM order_info WHERE order_id=?",(rs,n)->{var o=new OrderInfo();o.setOrderId(oid);o.setUserId(rs.getString("user_id"));o.setPayOrderId(rs.getString("pay_order_id"));o.setOrderStatus(rs.getInt("order_status"));o.setChannelOrderId(rs.getString("channel_order_Id"));return o;},oid));
        when(items.getOrderItemByOrderItemId(itemId)).thenAnswer(i->jdbc.queryForObject("SELECT * FROM order_item WHERE order_item_id=?",(rs,n)->{var item=new OrderItem();item.setOrderId(oid);item.setOrderItemId(itemId);item.setOrderItemStatus(rs.getInt("order_item_status"));item.setRefundAmount(rs.getBigDecimal("refund_amount"));item.setRefundOrderId(rs.getString("refund_order_id"));return item;},itemId));
        when(orders.findListByParam(any())).thenAnswer(i->List.of(orders.getOrderInfoByOrderId(oid)));
        doAnswer(i->((Callable<?>)i.getArgument(1)).call()).when(redis).runWithPayOrderLifecycleLock(anyString(),ArgumentMatchers.<Callable<Map<String,Object>>>any());
        doAnswer(i->{jdbc.update("UPDATE order_item SET order_item_status=0,refund_amount=99.00,refund_order_id=? WHERE order_item_id=?",id,itemId);jdbc.update("UPDATE order_info SET order_status=6 WHERE order_id=?",oid);return null;}).when(orders).refundAfterSale(any(),eq("AS_TEST_USER"),anyBoolean());
        new TransactionTemplate(manager).execute(status->{
            try {
                jdbc.update("INSERT INTO order_info(order_id,user_id,order_status,pay_order_id,channel_order_Id,amount) VALUES(?,'AS_TEST_USER',?,?,'LOCAL_DEMO_fixture',99.00)",oid,returning?3:1,oid);
                jdbc.update("INSERT INTO order_item(order_item_id,order_id,product_id,product_name,property_value_id_hash,order_item_status) VALUES(?,?,'AS_TEST_PRODUCT','rollback fixture','test',1)",itemId,oid);
                jdbc.update("INSERT INTO order_logistics_info(order_id,user_id,logistics_status) VALUES(?,'AS_TEST_USER',?)",oid,returning?2:0);
                jdbc.update("INSERT INTO after_sale_request(request_id,user_id,order_id,order_item_id,request_type,reason,status,policy_version) VALUES(?,'AS_TEST_USER',?,?,?,'test','PENDING_REVIEW','demo-v1')",id,oid,itemId,returning?"return_refund":"refund");
                assertEquals("PENDING_REVIEW",mapper.detail(id).get("phase"));
                service.review("admin",new AfterSaleDTO.Review(id,true,"批准测试"));
                if(returning){
                    assertEquals("WAIT_RETURN",mapper.detail(id).get("phase"));
                    service.submitReturn("AS_TEST_USER",new AfterSaleDTO.ReturnParcel(id,"demo","DEMO123456"));
                    assertEquals("WAIT_INSPECTION",mapper.detail(id).get("phase"));
                    service.inspect("admin",new AfterSaleDTO.Inspect(id,true,false,"损坏品不能再次销售"));
                }
                assertEquals("READY_REFUND",mapper.detail(id).get("phase"));
                var completed=service.refund("admin",id);
                assertEquals("COMPLETED",completed.get("status"));assertEquals("COMPLETED",completed.get("phase"));
                assertEquals(0,new java.math.BigDecimal("99.00").compareTo((java.math.BigDecimal)completed.get("refundAmount")));
                assertEquals("COMPLETED",service.refund("admin",id).get("status"));
                verify(orders,times(1)).refundAfterSale(any(),eq("AS_TEST_USER"),eq(!returning));verify(pay,times(1)).markRefunded(oid);
                assertTrue(mapper.logs(id).size()>2);
                assertEquals(1,mapper.mine("AS_TEST_USER").stream().filter(r->id.equals(r.get("requestId"))).count());
            } finally {status.setRollbackOnly();}
            return null;
        });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_request WHERE request_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_log WHERE request_id=?",Integer.class,id));
    }
}
