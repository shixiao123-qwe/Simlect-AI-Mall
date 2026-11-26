package com.simlect.biz;

import com.simlect.api.support.StockFeignSupport;
import com.simlect.api.vo.ProductSkuSnapshotVO;
import com.simlect.api.vo.ProductPropertyValueSnapshotVO;
import com.simlect.api.enums.ProductStatusEnum;
import com.simlect.entity.dto.AiProductFilterDTO;
import com.simlect.entity.po.SysCategory;
import com.simlect.entity.query.SysCategoryQuery;
import com.simlect.mappers.SysCategoryMapper;
import com.simlect.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AiProductFilterService {
    @Resource private ProductInternalService products;
    @Resource private StockFeignSupport stocks;
    @Resource private SysCategoryMapper<SysCategory,SysCategoryQuery> categories;

    public List<Map<String,Object>> filter(AiProductFilterDTO request) {
        if (request.productIds()==null || request.productIds().isEmpty() || request.productIds().size()>50)
            throw new BusinessException("候选商品数量必须为1至50");
        if(request.minPrice()!=null && request.maxPrice()!=null && request.minPrice().compareTo(request.maxPrice())>0)
            throw new BusinessException("价格区间无效");
        var ids=request.productIds().stream().distinct().toList();
        var snapshot=products.snapshotBatch(ids);
        if(snapshot.getSkus().size()>5000) throw new BusinessException("候选SKU过多");
        // One remote batch. A failed stock service is an error, never pretend stock exists.
        var available=new HashMap<String,Integer>();
        for(var stock:stocks.readBatch(ids)) available.put(stock.getProductId()+":"+stock.getPropertyValueIdHash(),stock.getStock()==null?0:stock.getStock());
        var categoryNames=new HashMap<String,String>();
        if(request.category()!=null && !request.category().isBlank())
            for(var c:categories.selectList(new SysCategoryQuery())) categoryNames.put(c.getCategoryId(),c.getCategoryName());
        var result=new ArrayList<Map<String,Object>>();
        var skuGroups=snapshot.getSkus().stream().collect(java.util.stream.Collectors.groupingBy(ProductSkuSnapshotVO::getProductId));
        var attrGroups=snapshot.getPropertyValues().stream().collect(java.util.stream.Collectors.groupingBy(ProductPropertyValueSnapshotVO::getProductId));
        for(var product:snapshot.getProducts()) {
            if(!ids.contains(product.getProductId()) || !ProductStatusEnum.ON_SALE.getStatus().equals(product.getStatus())) continue;
            if(request.category()!=null && !request.category().isBlank()) {
                String name=categoryNames.get(product.getCategoryId());
                String query=categoryName(request.category());
                if((name==null || !categoryName(name).contains(query)) && (product.getProductName()==null || !categoryName(product.getProductName()).contains(query))) continue;
            }
            for(var sku:skuGroups.getOrDefault(product.getProductId(),List.of())) {
                if(!product.getProductId().equals(sku.getProductId()) || sku.getPrice()==null
                        || sku.getPrice().signum()<=0 || sku.getPrice().scale()>2
                        || sku.getPropertyValueIdHash()==null || sku.getPropertyValueIdHash().isBlank()) continue;
                if(request.minPrice()!=null && sku.getPrice().compareTo(request.minPrice())<0) continue;
                if(request.maxPrice()!=null && sku.getPrice().compareTo(request.maxPrice())>0) continue;
                int stock=available.getOrDefault(product.getProductId()+":"+sku.getPropertyValueIdHash(),0);
                if(stock<=0) continue;
                var attrs=new HashMap<String,String>();
                var values=new HashSet<>(Arrays.asList((sku.getPropertyValueIds()==null?"":sku.getPropertyValueIds()).split("-")));
                for(var attr:attrGroups.getOrDefault(product.getProductId(),List.of()))
                    if(product.getProductId().equals(attr.getProductId()) && values.contains(attr.getPropertyValueId()) && attr.getPropertyName()!=null && attr.getPropertyValue()!=null)
                        attrs.merge(attr.getPropertyName(),attr.getPropertyValue(),(a,b)->a+" / "+b);
                if(request.hardAttributes()!=null && request.hardAttributes().stream().anyMatch(a -> !matches(attrs,a))) continue;
                var row=new LinkedHashMap<String,Object>();
                row.put("productId",product.getProductId()); row.put("productName",product.getProductName());
                row.put("cover",product.getCover()); row.put("categoryId",product.getCategoryId());
                row.put("skuHash",sku.getPropertyValueIdHash()); row.put("price",sku.getPrice());
                row.put("stock",stock); row.put("attributes",attrs); result.add(row);
            }
        }
        return result;
    }

    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+","");
    }

    private static String attributeName(String value) {
        return switch(normalized(value)) {
            case "色彩" -> "颜色";
            case "ram", "内存容量" -> "运行内存";
            case "rom", "机身存储" -> "存储容量";
            case "brand" -> "品牌";
            case "新旧" -> "成色";
            case "降噪功能" -> "降噪";
            default -> normalized(value);
        };
    }

    private static String attributeValue(String value) {
        String text=normalized(value);
        return switch(text) {
            case "apple" -> "苹果";
            case "huawei" -> "华为";
            case "xiaomi" -> "小米";
            case "sony" -> "索尼";
            case "anc" -> "主动降噪";
            default -> text.matches("[0-9]+(?:\\.[0-9]+)?gb") ? text.substring(0,text.length()-1) : text;
        };
    }

    private static String categoryName(String value) {
        return normalized(value).replace("智能手机","手机").replace("移动电话","手机").replace("笔记本电脑","笔记本");
    }

    private static boolean matches(Map<String,String> attrs,AiProductFilterDTO.Attribute wanted) {
        if(wanted==null || wanted.name()==null || wanted.value()==null) return false;
        String operation=wanted.operator()==null?"eq":wanted.operator();
        if(!Set.of("eq","ne","gte","lte").contains(operation)) throw new BusinessException("不支持的属性比较方式");
        var values=new ArrayList<String>();
        attrs.forEach((name,value)-> {
            if(attributeName(name).equals(attributeName(wanted.name())))
                values.addAll(Arrays.asList(value.split(" / ")));
        });
        // Unknown attributes cannot prove a positive OR a negative hard constraint.
        if(values.isEmpty() || values.stream().anyMatch(v->v.isBlank() || Set.of("未知","暂无","暂无数据","未提供").contains(v))) return false;
        if(Set.of("品牌","颜色","成色","运行内存","存储容量").contains(attributeName(wanted.name()))
                && values.stream().map(AiProductFilterService::attributeValue).distinct().count()>1) return false;
        if(operation.equals("gte") || operation.equals("lte")) {
            var pattern=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(g|tb|小时|mah|ms|hz)");
            var expected=pattern.matcher(attributeValue(wanted.value()));
            if(!expected.matches()) return false;
            return values.stream().anyMatch(value->{
                var actual=pattern.matcher(attributeValue(value));
                if(!actual.matches() || !actual.group(2).equals(expected.group(2))) return false;
                int comparison=new java.math.BigDecimal(actual.group(1)).compareTo(new java.math.BigDecimal(expected.group(1)));
                return operation.equals("gte")?comparison>=0:comparison<=0;
            });
        }
        boolean equal=values.stream().anyMatch(v->attributeValue(v).equals(attributeValue(wanted.value())));
        return operation.equals("eq")?equal:!equal;
    }
}
