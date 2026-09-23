package com.shop.marketing.promo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.entity.PromoLevel;
import com.shop.marketing.promo.entity.PromoTarget;
import com.shop.marketing.promo.mapper.PromoLevelMapper;
import com.shop.marketing.promo.mapper.PromoMapper;
import com.shop.marketing.promo.mapper.PromoTargetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 促销规则只读查询（试算引擎依赖）。一次性按类型批量取出在效促销、档位与目标，避免逐 SKU 查库。
 */
@Component
@RequiredArgsConstructor
public class PromoQueryService {

    private final PromoMapper promoMapper;
    private final PromoLevelMapper levelMapper;
    private final PromoTargetMapper targetMapper;

    /** 查询给定类型集合中当前在效且上架的促销。 */
    public List<Promo> activePromos(Collection<Integer> types, LocalDateTime now) {
        if (types == null || types.isEmpty()) {
            return Collections.emptyList();
        }
        return promoMapper.selectList(new LambdaQueryWrapper<Promo>()
                .eq(Promo::getStatus, 1)
                .in(Promo::getType, types)
                .le(Promo::getStartTime, now)
                .ge(Promo::getEndTime, now));
    }

    public Map<Long, List<PromoLevel>> levels(Collection<Long> promoIds) {
        if (promoIds == null || promoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return levelMapper.selectList(new LambdaQueryWrapper<PromoLevel>()
                        .in(PromoLevel::getPromoId, promoIds))
                .stream().collect(Collectors.groupingBy(PromoLevel::getPromoId));
    }

    public Map<Long, List<PromoTarget>> targets(Collection<Long> promoIds) {
        if (promoIds == null || promoIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, List<PromoTarget>> result = new HashMap<>(promoMapper.selectCount(null) == 0 ? 8 : 8);
        targetMapper.selectList(new LambdaQueryWrapper<PromoTarget>()
                        .in(PromoTarget::getPromoId, promoIds))
                .forEach(t -> result.computeIfAbsent(t.getPromoId(), k -> new java.util.ArrayList<>()).add(t));
        return result;
    }
}
