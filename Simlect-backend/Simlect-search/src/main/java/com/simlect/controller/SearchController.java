package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.SearchKeywordService;
import com.simlect.biz.SearchRecommendService;
import com.simlect.component.EsSearchComponent;
import com.simlect.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Set;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/search")
@RestController
public class SearchController extends ABaseController {

    @Resource
    private EsSearchComponent esSearchComponent;

    @PostMapping("/products")
    public ResponseVO searchProducts(String keyWords, BigDecimal priceFrom, BigDecimal priceTo,
                                     String sortType, String sortField, Integer pageNo) {
        if (keyWords == null || keyWords.isBlank() || keyWords.length() > 200) {
            throw new BusinessException("搜索关键词不能为空且不能超过200个字符");
        }
        if ((priceFrom != null && priceFrom.signum() < 0)
                || (priceTo != null && priceTo.signum() < 0)
                || (priceFrom != null && priceTo != null && priceFrom.compareTo(priceTo) > 0)) {
            throw new BusinessException("价格区间无效");
        }
        // ES defaults to a 10,000-hit result window; 666 pages * 15 stays within it.
        if (pageNo != null && (pageNo < 1 || pageNo > 666)) {
            throw new BusinessException("页码必须在1至666之间");
        }
        if (sortField != null && !Set.of("composite", "price", "sale").contains(sortField)) {
            throw new BusinessException("排序字段无效");
        }
        if (sortType != null && !Set.of("asc", "desc").contains(sortType)) {
            throw new BusinessException("排序方向无效");
        }
        return getSuccessResponseVO(esSearchComponent.searchProducts(keyWords.trim(), priceFrom, priceTo,
                sortType, sortField, pageNo));
    }

    @Resource
    private SearchKeywordService searchKeywordService;
    @Resource
    private SearchRecommendService searchRecommendService;

    @GetMapping("/loadHotKeywords")
    public ResponseVO loadHotKeywords() {
        return getSuccessResponseVO(searchKeywordService.loadHotKeywords());
    }

    @GetMapping("/loadRecentKeywords")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadRecentKeywords() {
        return getSuccessResponseVO(searchKeywordService.loadRecentKeywords(getTokenUserInfo().getUserId()));
    }

    @PostMapping("/saveKeyword")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO saveKeyword(@NotEmpty String keyword) {
        searchKeywordService.saveUserKeyword(getTokenUserInfo().getUserId(), keyword);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/clearRecentKeywords")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO clearRecentKeywords() {
        searchKeywordService.clearUserKeywords(getTokenUserInfo().getUserId());
        return getSuccessResponseVO(null);
    }

    @PostMapping("/removeRecentKeyword")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO removeRecentKeyword(@NotEmpty String keyword) {
        searchKeywordService.removeUserKeyword(getTokenUserInfo().getUserId(), keyword);
        return getSuccessResponseVO(null);
    }

    @GetMapping("/loadGuessKeywords")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadGuessKeywords() {
        return getSuccessResponseVO(searchRecommendService.loadGuessKeywords(getTokenUserInfo().getUserId()));
    }

    @GetMapping("/loadRecommendProducts")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadRecommendProducts(Integer limit) {
        int size = limit == null ? 8 : limit;
        return getSuccessResponseVO(searchRecommendService.loadRecommendProducts(getTokenUserInfo().getUserId(), size));
    }
}
