package com.simlect.biz;
import com.simlect.mappers.SkuStockMapper;
import com.simlect.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StockReadBatchTest {
    @Test void rejectsEmptyAndOversizedWithoutSql() {
        var mapper=mock(SkuStockMapper.class);var service=new SkuStockService();ReflectionTestUtils.setField(service,"skuStockMapper",mapper);
        assertThrows(BusinessException.class,()->service.readBatch(List.of()));
        assertThrows(BusinessException.class,()->service.readBatch(Collections.nCopies(51,"p")));
        verifyNoInteractions(mapper);
    }
    @Test void deduplicatesBeforeSingleSql() {
        var mapper=mock(SkuStockMapper.class);var service=new SkuStockService();ReflectionTestUtils.setField(service,"skuStockMapper",mapper);
        when(mapper.selectByProductIds(List.of("p"))).thenReturn(List.of());
        assertTrue(service.readBatch(List.of("p","p")).isEmpty());verify(mapper).selectByProductIds(List.of("p"));
    }
}
