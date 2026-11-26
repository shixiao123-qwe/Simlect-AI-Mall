package com.simlect.controller;

import com.simlect.biz.impl.ProductInfoServiceImpl;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.mappers.ProductInfoMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only. All rows are isolated by random category and rolled back. */
@EnabledIfEnvironmentVariable(named="SIMLECT_PRODUCT_DB_TEST", matches="true")
class ProductPaginationMySqlTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tiedSortValuesAcrossPagesRemainCompleteAndRepeatable() throws Exception {
        String password=System.getenv("MYSQL_PASSWORD");
        assertNotNull(password,"Explicit local test credential required");
        int port=Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT","3306"));
        assertTrue(port>=1 && port<=65535,"MYSQL_PORT must be between 1 and 65535");
        DriverManagerDataSource ds=new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:"+port+"/simlect_product?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",
                System.getenv().getOrDefault("MYSQL_USER","root"),password);
        Configuration config=new Configuration(new Environment("local-product-rollback",new JdbcTransactionFactory(),ds));
        config.addMapper(ProductInfoMapper.class);
        SqlSessionFactory factory=new SqlSessionFactoryBuilder().build(config);
        String fixture=UUID.randomUUID().toString().replace("-","").substring(0,7).toUpperCase(Locale.ROOT);
        String category="T"+fixture;
        String parent="U"+fixture;
        List<String> expected=new ArrayList<>();

        try(SqlSession session=factory.openSession(false)) {
            try {
                var connection=session.getConnection();
                try(var insert=connection.prepareStatement("INSERT INTO product_info(product_id,product_name,category_id,p_category_id,status,min_price,max_price,total_sale,commend_type,create_time) VALUES(?,?,?,?,?,199,199,0,1,'2026-01-01 00:00:00')")) {
                    // Reverse insertion order so a stable id ordering cannot pass accidentally.
                    for(int i=39;i>=0;i--) {
                        String id="PT"+fixture+String.format(Locale.ROOT,"%02d",i);
                        insert.setString(1,id);insert.setString(2,"rollback pagination fixture");
                        insert.setString(3,category);insert.setString(4,parent);
                        insert.setInt(5,i<37?1:0);insert.executeUpdate();
                        if(i<37) expected.add(id);
                    }
                }
                Collections.sort(expected);
                ProductInfoServiceImpl service=new ProductInfoServiceImpl();
                ReflectionTestUtils.setField(service,"productInfoMapper",session.getMapper(ProductInfoMapper.class));
                ProductController controller=new ProductController();
                ReflectionTestUtils.setField(controller,"productInfoService",service);

                for(String categoryFilter:List.of(category,parent)) {
                    for(String field:List.of("default","price","sale")) {
                        for(String direction:List.of("asc","desc")) {
                            List<String> first=readPages(controller,categoryFilter,field,direction);
                            List<String> again=readPages(controller,categoryFilter,field,direction);
                            assertEquals(expected,first,"Stable full result for "+field+"/"+direction);
                            assertEquals(first,again,"Repeated pages must keep their boundaries");
                            assertEquals(first.size(),new HashSet<>(first).size(),"Pages must not overlap");
                        }
                    }
                }
            } finally { session.rollback(true); }
        }
        try(var connection=ds.getConnection();var query=connection.prepareStatement("SELECT count(*) FROM product_info WHERE category_id=?")) {
            query.setString(1,category);
            try(var rows=query.executeQuery()) {rows.next();assertEquals(0,rows.getInt(1),"Fixture transaction must roll back");}
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readPages(ProductController controller,String category,String field,String direction) {
        List<String> result=new ArrayList<>();
        for(int page=1;page<=3;page++) {
            var response=controller.loadProduct(page,category,null,null,field,direction,true);
            var data=(PaginationResultVO<ProductInfo>)response.getData();
            assertEquals(37,data.getTotalCount());assertEquals(3,data.getPageTotal());
            result.addAll(data.getList().stream().map(ProductInfo::getProductId).toList());
        }
        return result;
    }
}
