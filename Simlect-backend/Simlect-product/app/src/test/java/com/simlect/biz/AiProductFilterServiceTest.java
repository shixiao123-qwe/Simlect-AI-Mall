package com.simlect.biz;

import com.simlect.api.dto.*;
import com.simlect.api.vo.*;
import com.simlect.api.support.StockFeignSupport;
import com.simlect.entity.dto.AiProductFilterDTO;
import com.simlect.entity.po.SysCategory;
import com.simlect.entity.query.SysCategoryQuery;
import com.simlect.mappers.SysCategoryMapper;
import com.simlect.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiProductFilterServiceTest {
    @Mock ProductInternalService products;
    @Mock StockFeignSupport stocks;
    @Mock SysCategoryMapper<SysCategory,SysCategoryQuery> categories;
    @InjectMocks AiProductFilterService service;
    ProductSnapshotBatchVO snapshot;
    ProductInfoSnapshotVO product;
    ProductSkuSnapshotVO cheap,expensive;
    @BeforeEach void setup() {
        product=new ProductInfoSnapshotVO(); product.setProductId("p1"); product.setProductName("蓝牙耳机"); product.setStatus(1);
        cheap=new ProductSkuSnapshotVO(); cheap.setProductId("p1"); cheap.setPropertyValueIdHash("s1"); cheap.setPropertyValueIds("a-b"); cheap.setPrice(new BigDecimal("299.99"));
        expensive=new ProductSkuSnapshotVO(); expensive.setProductId("p1"); expensive.setPropertyValueIdHash("s2"); expensive.setPrice(new BigDecimal("399"));
        var attr=new ProductPropertyValueSnapshotVO(); attr.setProductId("p1"); attr.setPropertyValueId("a"); attr.setPropertyName("成色"); attr.setPropertyValue("全新");
        snapshot=new ProductSnapshotBatchVO(); snapshot.setProducts(List.of(product)); snapshot.setSkus(List.of(cheap,expensive)); snapshot.setPropertyValues(List.of(attr));
        lenient().when(products.snapshotBatch(any())).thenReturn(snapshot);
        lenient().when(stocks.readBatch(any())).thenReturn(List.of(new SkuStockDTO("p1","s1",2),new SkuStockDTO("p1","s2",5)));
    }
    AiProductFilterDTO request(String max,List<AiProductFilterDTO.Attribute> attrs) {
        return new AiProductFilterDTO(List.of("p1"),null,max==null?null:new BigDecimal(max),null,attrs);
    }
    void addAttribute(String name,String value) {
        var attr=new ProductPropertyValueSnapshotVO();attr.setProductId("p1");attr.setPropertyValueId("b");
        attr.setPropertyName(name);attr.setPropertyValue(value);
        var attrs=new ArrayList<>(snapshot.getPropertyValues());attrs.add(attr);snapshot.setPropertyValues(attrs);
    }
    @Test void negativeAttributeRejectsMissingEvidenceToo() {
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("品牌","索尼","ne")))).isEmpty());
    }
    @Test void excludedBrandUsesBusinessAttributeAndKnownAlias() {
        addAttribute("Brand","Sony");
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("品牌","索尼","ne")))).isEmpty());
        var result=service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("品牌","Apple","ne"))));
        assertEquals(1,result.size());assertEquals("s1",result.get(0).get("skuHash"));
    }
    @Test void colorAliasDoesNotRelaxOtherHardAttributes() {
        addAttribute("色彩","黑色");
        assertEquals(1,service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("颜色","黑色")))).size());
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("颜色","黑色","ne")))).isEmpty());
    }
    @Test void ramUnitsAreEquivalentWithoutSubstringMatching() {
        addAttribute("RAM","8GB");
        assertEquals(1,service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("运行内存","8G")))).size());
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("运行内存","18G")))).isEmpty());
    }
    @Test void numericAttributeBoundsRequireKnownCompatibleUnits() {
        addAttribute("RAM","16GB");
        assertEquals(1,service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("运行内存","8G","gte")))).size());
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("运行内存","8G","lte")))).isEmpty());
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("运行内存","8TB","gte")))).isEmpty());
    }
    @Test void unknownAttributeValueCannotSatisfyExclusion() {
        addAttribute("品牌","未知");
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("品牌","索尼","ne")))).isEmpty());
    }
    @Test void ancAliasDoesNotTurnUnsupportedNoiseCancellationIntoSupport() {
        addAttribute("降噪功能","不支持主动降噪");
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("降噪","ANC")))).isEmpty());
        snapshot.setPropertyValues(snapshot.getPropertyValues().subList(0,1));addAttribute("降噪功能","主动降噪");
        assertEquals(1,service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("降噪","ANC")))).size());
    }
    @Test void latestStockIsAlwaysReadInsteadOfReusingPreviousEligibility() {
        assertEquals(1,service.filter(request("300",null)).size());
        when(stocks.readBatch(any())).thenReturn(List.of(new SkuStockDTO("p1","s1",0),new SkuStockDTO("p1","s2",0)));
        assertTrue(service.filter(request("300",null)).isEmpty());
        verify(stocks,times(2)).readBatch(List.of("p1"));
    }
    @Test void invalidSkuPriceCannotBeRecommended() {
        cheap.setPrice(BigDecimal.ZERO);assertTrue(service.filter(request("300",null)).isEmpty());
        cheap.setPrice(new BigDecimal("299.999"));assertTrue(service.filter(request("300",null)).isEmpty());
    }
    @Test void minimumAndMaximumBudgetAreBothInclusive() {
        var exact=new AiProductFilterDTO(List.of("p1"),new BigDecimal("299.99"),new BigDecimal("299.99"),null,null);
        assertEquals(1,service.filter(exact).size());
    }
    @Test void categoryAliasesAreConservativeAndDoNotInventSubtype() {
        product.setProductName("移动电话");
        when(categories.selectList(any())).thenReturn(List.of());
        assertEquals(2,service.filter(new AiProductFilterDTO(List.of("p1"),null,null,"智能手机",null)).size());
        assertTrue(service.filter(new AiProductFilterDTO(List.of("p1"),null,null,"耳机",null)).isEmpty());
    }
    @Test void unknownOperatorCannotBypassHardFilter() {
        assertThrows(BusinessException.class,()->service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("成色","全新","gt")))));
    }
    @Test void filtersByRealSkuPriceAndUsesOneBatch() {
        var result=service.filter(request("300",null)); assertEquals(1,result.size());
        assertEquals("s1",result.get(0).get("skuHash")); verify(stocks,times(1)).readBatch(List.of("p1"));
    }
    @Test void budgetBoundaryIsInclusive() { assertEquals(1,service.filter(request("299.99",null)).size()); }
    @Test void belowBoundaryReturnsEmpty() { assertTrue(service.filter(request("299.98",null)).isEmpty()); }
    @Test void offSaleIsExcluded() { product.setStatus(0); assertTrue(service.filter(request(null,null)).isEmpty()); }
    @Test void missingStockIsNotAvailable() { when(stocks.readBatch(any())).thenReturn(List.of()); assertTrue(service.filter(request(null,null)).isEmpty()); }
    @Test void hardAttributeOnlyMatchesSkuValues() {
        assertEquals(1,service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("成色","全新")))).size());
        assertTrue(service.filter(request(null,List.of(new AiProductFilterDTO.Attribute("延迟","10ms")))).isEmpty());
    }
    @Test void stockFailureMustNotReturnProducts() {
        when(stocks.readBatch(any())).thenThrow(new BusinessException("unavailable"));
        assertThrows(BusinessException.class,()->service.filter(request(null,null)));
    }
    @Test void reversedIntervalRejectedBeforeDb() {
        var req=new AiProductFilterDTO(List.of("p1"),new BigDecimal("400"),new BigDecimal("300"),null,null);
        assertThrows(BusinessException.class,()->service.filter(req)); verifyNoInteractions(products,stocks);
    }
}
