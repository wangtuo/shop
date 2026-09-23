package com.shop.marketing.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.dto.CalcItem;
import com.shop.api.marketing.dto.ItemPriceDetail;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.marketing.enums.CouponStatuses;
import com.shop.api.marketing.enums.CouponTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.common.util.MoneyUtils;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.support.ActivityRule;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.CouponTarget;
import com.shop.marketing.coupon.entity.UserCoupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.CouponTargetMapper;
import com.shop.marketing.coupon.mapper.UserCouponMapper;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.entity.PromoLevel;
import com.shop.marketing.promo.entity.PromoTarget;
import com.shop.marketing.promo.service.PromoQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 六层优惠叠加试算引擎（design.md 4.2.1 顺序 / 4.2.2 最大余数分摊 / 4.2.3 互斥）：
 * <pre>
 * 会员等级折扣（折入商品层）→ 1 限时折扣 → 2 满减/满折(互斥取最优)/第N件/满赠
 *   → 3 品类券 → 4 店铺券 → 5 平台券（同层 1 张）→ 免邮券抵运费
 *   → 6 积分抵现（100:1，单笔商品金额 50% 封顶）→ 实付
 * </pre>
 * 秒杀互斥一切；拼团仅成团价+运费；预售券仅尾款阶段可用。
 */
@Component
@RequiredArgsConstructor
public class PriceEngine {

    /** 会员等级折扣基点：L0 1.0 / L1 0.98 / L2 0.95 / L3 0.92 / L4 0.90 */
    private static final int[] LEVEL_BP = {1000, 980, 950, 920, 900};

    private final PromoQueryService promoQueryService;
    private final CouponMapper couponMapper;
    private final CouponTargetMapper couponTargetMapper;
    private final UserCouponMapper userCouponMapper;
    private final ActivityMapper activityMapper;

    public PriceCalcResult calculate(PriceCalcCommand cmd) {
        int orderType = cmd.getOrderType() == null ? 1 : cmd.getOrderType();
        boolean seckill = orderType == 2;
        boolean groupbuy = orderType == 3;
        boolean presale = orderType == 4;
        boolean finalStage = Boolean.TRUE.equals(cmd.getPresaleFinalStage());

        List<CalcLine> lines = new ArrayList<>(cmd.getItems().size());
        for (CalcItem item : cmd.getItems()) {
            lines.add(new CalcLine(item));
        }

        // 互斥前置：秒杀/拼团禁券；预售仅尾款可用券
        boolean anyCoupon = cmd.getCategoryCouponId() != null || cmd.getShopCouponId() != null
                || cmd.getPlatformCouponId() != null;
        if ((seckill || groupbuy) && anyCoupon) {
            throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE,
                    seckill ? "秒杀订单与所有优惠券互斥" : "拼团订单不可使用优惠券");
        }
        if (presale && !finalStage && anyCoupon) {
            throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "预售定金阶段不可使用优惠券");
        }
        long wantPoints = cmd.getUsePointsFen() == null ? 0L : Math.max(0L, cmd.getUsePointsFen());
        if ((seckill || groupbuy) && wantPoints > 0) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE,
                    seckill ? "秒杀订单不可使用积分抵现" : "拼团订单不可使用积分抵现");
        }

        // 活动价快照层（B1 团长价 / B3 砍价成交价通用；B5 预售尾款膨胀抵扣）：
        // 在一切普通促销/券/积分之前先改写商品行金额，后续各层只见到折后基数，天然杜绝重复抵扣。
        boolean leader = groupbuy && Integer.valueOf(1).equals(cmd.getLeaderFlag());
        long activityPriceTotal = applyActivityPriceLayer(cmd, lines, groupbuy, presale, finalStage, leader);

        // 促销规则批量预取
        LocalDateTime now = LocalDateTime.now();
        List<Promo> promos = (seckill || groupbuy)
                ? Collections.emptyList()
                : filterInWindow(promoQueryService.activePromos(List.of(1, 2, 3, 4, 5), now), now);
        Map<Long, List<PromoLevel>> levelMap = promoQueryService.levels(promoIds(promos));
        Map<Long, List<PromoTarget>> targetMap = promoQueryService.targets(promoIds(promos));

        long productPromoTotal = activityPriceTotal
                + applyProductLayer(cmd, lines, seckill, groupbuy, promos, levelMap, targetMap);
        long shopPromoTotal = applyShopLayer(lines, promos, levelMap, targetMap);
        LinkedHashMap<Long, Integer> gifts = collectGifts(lines, promos, levelMap, targetMap);

        // 券 3/4/5 层（同层 1 张）+ 免邮券
        CouponOutcome outcome = applyCouponLayers(cmd, lines, seckill, groupbuy, presale, finalStage);
        long categoryCoupon = outcome.category;
        long shopCoupon = outcome.shop;
        long platformCoupon = outcome.platform;
        long freightCoupon = outcome.freight;

        // 第 6 层：积分抵现，100:1（上送即分），单笔商品金额 50% 封顶
        long pointsDeduct = applyPoints(lines, wantPoints);

        long freight = Math.max(0L, cmd.getFreightFen() == null ? 0L : cmd.getFreightFen());
        long finalFreight = Math.max(0L, freight - freightCoupon);
        applyFreight(lines, finalFreight);

        PriceCalcResult result = buildResult(lines, productPromoTotal, shopPromoTotal,
                categoryCoupon, shopCoupon, platformCoupon, freightCoupon, pointsDeduct, finalFreight, gifts);
        result.setUsedUserCouponIds(outcome.usedIds);
        // 拼团回填团长口径：团长价（不含运费的商品应付总额）与团长标记
        if (groupbuy) {
            result.setLeaderFlag(leader);
            if (leader) {
                result.setLeaderPriceFen(Math.max(0L, result.getPayFen() - result.getFreightFen()));
            }
        }
        // 分摊守恒断言：明细各层合计必须等于整单金额（不差 1 分）
        assertConservation(result, freight);
        result.setSnapshotJson(JsonUtils.toJson(result));
        return result;
    }

    // ------------------------------------------------------------------
    // 活动价快照层：拼团团长价（B1）/ 砍价成交价快照（B3 通用）/ 预售尾款膨胀抵扣（B5）
    // ------------------------------------------------------------------

    /**
     * 活动价/膨胀先于一切普通促销、券、积分改写商品行，后续各层只见到折后基数，杜绝重复抵扣。
     * 返回计入商品层（productPromoFen）的活动价优惠总额。
     *
     * <p>取价规则（{@link CalcItem#getActivityPriceFen()}）：
     * <ul>
     *   <li>非空 = 调用方给定最终活动成交价快照（拼团团长价/砍价成交价），直接以此为基数，
     *       绝不再减团长优惠——重复抵扣在这一层被结构禁止；</li>
     *   <li>为空且拼团团长（leaderFlag=1）：团长活动价 = max(拼团价 - leaderDiscountFen, 0)，
     *       leaderDiscountFen 取自 t_activity.rule_json；团员/历史调用维持 salePriceFen。</li>
     * </ul>
     *
     * <p>预售尾款（presaleFinalStage=true，B5）价格恒等式：
     * <pre>尾款应付 = 尾款原价 - 膨胀抵扣（inflateDeductFen，定金 50 抵 100 即抵 100）</pre>
     * 膨胀抵扣按折前各商品行金额占比最大余数法分摊、底价 0；定金阶段（finalStage=false/null）
     * 永不应用——定金不退，膨胀只在尾款计价且仅在本方法入账一次。
     */
    private long applyActivityPriceLayer(PriceCalcCommand cmd, List<CalcLine> lines,
                                         boolean groupbuy, boolean presale, boolean finalStage,
                                         boolean leader) {
        long total = 0L;
        long leaderDiscount = 0L;
        if (groupbuy && cmd.getGroupbuyActivityId() != null) {
            ActivityRule rule = loadRule(cmd.getGroupbuyActivityId());
            if (leader && rule != null && rule.getLeaderDiscountFen() != null) {
                leaderDiscount = Math.max(0L, rule.getLeaderDiscountFen());
            }
        }
        // 1) 逐行活动价快照 / 团长价（固定单价 × 数量落明细）
        for (CalcLine line : lines) {
            Long snapshot = line.getItem().getActivityPriceFen();
            long saleUnit = line.getItem().getSalePriceFen() == null
                    ? 0L : Math.max(0L, line.getItem().getSalePriceFen());
            Long targetUnit = null;
            if (snapshot != null) {
                targetUnit = Math.max(0L, snapshot);
            } else if (groupbuy) {
                targetUnit = leader ? Math.max(0L, saleUnit - leaderDiscount) : saleUnit;
            }
            if (targetUnit != null) {
                line.setPriceLocked(true);
                long target = targetUnit * line.getItem().getQty();
                long d = line.getOriginal() - target;
                if (d > 0) {
                    line.setProductPromoAlloc(line.getProductPromoAlloc() + d);
                    line.setCur(line.getCur() - d);
                    total += d;
                }
            }
        }
        // 2) 预售尾款膨胀抵扣：整单固定金额，按折前各行金额占比最大余数法分摊，封顶至底价 0
        if (presale && finalStage && cmd.getPresaleActivityId() != null) {
            ActivityRule rule = loadRule(cmd.getPresaleActivityId());
            long inflate = rule == null || rule.getInflateDeductFen() == null
                    ? 0L : Math.max(0L, rule.getInflateDeductFen());
            long base = lines.stream().mapToLong(CalcLine::getCur).sum();
            long deduct = Math.min(inflate, base);
            if (deduct > 0) {
                List<Long> allocs = MoneyUtils.allocate(deduct, lines.stream().map(CalcLine::weight).toList());
                long allocated = 0L;
                for (int i = 0; i < lines.size(); i++) {
                    long a = allocs.get(i);
                    if (a <= 0) {
                        continue;
                    }
                    CalcLine line = lines.get(i);
                    line.setProductPromoAlloc(line.getProductPromoAlloc() + a);
                    line.setCur(line.getCur() - a);
                    allocated += a;
                }
                // 守恒断言：Σ折后行金额 = 尾款原价 - 膨胀抵扣；本方法是膨胀唯一入账点（定金不退，禁止重复抵扣）
                long after = lines.stream().mapToLong(CalcLine::getCur).sum();
                if (allocated != deduct || after != base - deduct) {
                    throw new IllegalStateException(
                            "预售膨胀抵扣分摊不守恒：deduct=" + deduct + ", allocated=" + allocated);
                }
                total += allocated;
            }
        }
        return total;
    }

    /** 读取活动规则 JSON；活动不存在/规则缺失返回 null（调用方按 0 活动优惠降级，不回退试算）。 */
    private ActivityRule loadRule(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getRuleJson() == null || activity.getRuleJson().isBlank()) {
            return null;
        }
        return JsonUtils.fromJson(activity.getRuleJson(), ActivityRule.class);
    }

    // ------------------------------------------------------------------
    // 商品层：会员等级折扣 + 限时折扣（互斥取最低，折入 PRODUCT 层）
    // ------------------------------------------------------------------
    private long applyProductLayer(PriceCalcCommand cmd, List<CalcLine> lines, boolean seckill, boolean groupbuy,
                                   List<Promo> promos, Map<Long, List<PromoLevel>> levelMap,
                                   Map<Long, List<PromoTarget>> targetMap) {
        long total = 0L;
        int level = cmd.getUserLevel() == null ? 0 : Math.max(0, Math.min(4, cmd.getUserLevel()));
        int memberBp = LEVEL_BP[level];
        List<Promo> limited = filter(promos, 5);
        for (CalcLine line : lines) {
            long d = 0L;
            // 秒杀价/拼团价/活动价快照行由订单域上送，不再叠加商品级常规优惠（会员折扣/限时折扣）
            if (!seckill && !groupbuy && !line.isPriceLocked()) {
                long memberD = 0L;
                if (memberBp < 1000) {
                    memberD = bpDiscount(line.getOriginal(), memberBp);
                    line.setCur(line.getOriginal() - memberD);
                }
                long bestLimited = 0L;
                for (Promo p : limited) {
                    if (!matches(p, targetMap.get(p.getId()), line.getItem())) {
                        continue;
                    }
                    for (PromoLevel lv : levelMap.getOrDefault(p.getId(), List.of())) {
                        bestLimited = Math.max(bestLimited, bpDiscount(line.getCur(),
                                lv.getDiscountBp() == null ? 1000 : lv.getDiscountBp()));
                    }
                }
                line.setCur(line.getCur() - bestLimited);
                d = memberD + bestLimited;
            }
            // 累加而非覆盖：活动价快照层（团长价/砍价/膨胀）已先写入 productPromoAlloc
            line.setProductPromoAlloc(line.getProductPromoAlloc() + d);
            total += d;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 店铺层：满减 vs 满折互斥取最优；第N件可叠加；满赠只产出赠品
    // ------------------------------------------------------------------
    private long applyShopLayer(List<CalcLine> lines, List<Promo> promos,
                                Map<Long, List<PromoLevel>> levelMap,
                                Map<Long, List<PromoTarget>> targetMap) {
        long total = 0L;
        Map<Long, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            groups.computeIfAbsent(lines.get(i).getItem().getShopId(), k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Long, List<Integer>> entry : groups.entrySet()) {
            Long shopId = entry.getKey();
            List<Integer> idx = entry.getValue();

            // 满减 / 满折（互斥，取优惠金额最大者）
            long bestReduce = 0L;
            List<Integer> reduceIdx = null;
            long bestDiscount = 0L;
            List<Integer> discountIdx = null;
            for (Promo p : promos) {
                if (!Objects.equals(p.getShopId(), shopId) || (p.getType() != 1 && p.getType() != 2)) {
                    continue;
                }
                List<PromoTarget> ts = targetMap.getOrDefault(p.getId(), List.of());
                List<Integer> applicable = new ArrayList<>();
                long subtotal = 0L;
                for (int i : idx) {
                    if (matches(p, ts, lines.get(i).getItem())) {
                        applicable.add(i);
                        subtotal += lines.get(i).getCur();
                    }
                }
                if (applicable.isEmpty()) {
                    continue;
                }
                PromoLevel lv = bestLevel(levelMap.getOrDefault(p.getId(), List.of()), subtotal);
                if (lv == null) {
                    continue;
                }
                if (p.getType() == 1) {
                    long d = lv.getReduceFen();
                    if (d > bestReduce) {
                        bestReduce = d;
                        reduceIdx = applicable;
                    }
                } else {
                    long d = bpDiscount(subtotal, lv.getDiscountBp() == null ? 1000 : lv.getDiscountBp());
                    if (d > bestDiscount) {
                        bestDiscount = d;
                        discountIdx = applicable;
                    }
                }
            }
            if (bestReduce >= bestDiscount && bestReduce > 0) {
                total += distribute(lines, reduceIdx, bestReduce, true);
            } else if (bestDiscount > 0) {
                total += distribute(lines, discountIdx, bestDiscount, true);
            }

            // 第 N 件优惠（与满减/满折不互斥，同类型取最优）
            for (int i : idx) {
                CalcLine line = lines.get(i);
                long bestNth = 0L;
                for (Promo p : promos) {
                    if (p.getType() != 4 || !Objects.equals(p.getShopId(), shopId)) {
                        continue;
                    }
                    if (!matches(p, targetMap.getOrDefault(p.getId(), List.of()), line.getItem())) {
                        continue;
                    }
                    for (PromoLevel lv : levelMap.getOrDefault(p.getId(), List.of())) {
                        int n = lv.getNthIndex() == null ? 0 : lv.getNthIndex();
                        if (n <= 0) {
                            continue;
                        }
                        long unitBase = line.getCur() / line.getItem().getQty();
                        long units = line.getItem().getQty() / n;
                        long d = units * bpDiscount(unitBase, lv.getDiscountBp() == null ? 1000 : lv.getDiscountBp());
                        bestNth = Math.max(bestNth, d);
                    }
                }
                bestNth = Math.min(bestNth, line.getCur());
                if (bestNth > 0) {
                    line.setShopPromoAlloc(line.getShopPromoAlloc() + bestNth);
                    line.setCur(line.getCur() - bestNth);
                    total += bestNth;
                }
            }
        }
        return total;
    }

    /** 满赠：达门槛的促销赠品 SKU 收集（不影响价格）。 */
    private LinkedHashMap<Long, Integer> collectGifts(List<CalcLine> lines, List<Promo> promos,
                                                      Map<Long, List<PromoLevel>> levelMap,
                                                      Map<Long, List<PromoTarget>> targetMap) {
        LinkedHashMap<Long, Integer> gifts = new LinkedHashMap<>();
        Map<Long, long[]> shopSubtotal = new LinkedHashMap<>();
        for (CalcLine line : lines) {
            long[] v = shopSubtotal.computeIfAbsent(line.getItem().getShopId(), k -> new long[1]);
            v[0] += line.getCur();
        }
        for (Promo p : promos) {
            if (p.getType() != 3) {
                continue;
            }
            long subtotal = shopSubtotal.getOrDefault(p.getShopId(), new long[1])[0];
            List<PromoTarget> ts = targetMap.getOrDefault(p.getId(), List.of());
            if (p.getScopeType() != null && p.getScopeType() != 1) {
                subtotal = 0L;
                for (CalcLine line : lines) {
                    if (Objects.equals(p.getShopId(), line.getItem().getShopId()) && matches(p, ts, line.getItem())) {
                        subtotal += line.getCur();
                    }
                }
            }
            PromoLevel lv = bestLevel(levelMap.getOrDefault(p.getId(), List.of()), subtotal);
            if (lv != null && lv.getGiftSkuId() != null && lv.getGiftQty() != null && lv.getGiftQty() > 0) {
                gifts.merge(lv.getGiftSkuId(), lv.getGiftQty(), Integer::sum);
            }
        }
        return gifts;
    }

    // ------------------------------------------------------------------
    // 券三层 + 免邮券结果
    // ------------------------------------------------------------------
    private static final class CouponOutcome {
        private long category;
        private long shop;
        private long platform;
        private long freight;
        private final List<Long> usedIds = new ArrayList<>();
    }

    private CouponOutcome applyCouponLayers(PriceCalcCommand cmd, List<CalcLine> lines,
                                            boolean seckill, boolean groupbuy, boolean presale,
                                            boolean finalStage) {
        CouponOutcome out = new CouponOutcome();
        if (seckill || groupbuy || (presale && !finalStage)) {
            return out;
        }
        Long[] slotIds = {cmd.getCategoryCouponId(), cmd.getShopCouponId(), cmd.getPlatformCouponId()};
        for (int slot = 0; slot < slotIds.length; slot++) {
            Long id = slotIds[slot];
            if (id == null) {
                continue;
            }
            UserCoupon uc = userCouponMapper.selectById(id);
            if (uc == null) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不存在：" + id);
            }
            if (cmd.getUserId() != null && !cmd.getUserId().equals(uc.getUserId())) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不属于当前用户");
            }
            if (uc.getStatus() != CouponStatuses.UNUSED.getCode()) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券状态不可用");
            }
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(uc.getValidStartTime()) || now.isAfter(uc.getValidEndTime())) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不在有效期内");
            }
            Coupon coupon = couponMapper.selectById(uc.getCouponId());
            if (coupon == null || coupon.getStatus() != 1) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券模板不可用");
            }
            List<CouponTarget> targets = couponTargetMapper.selectList(new LambdaQueryWrapper<CouponTarget>()
                    .eq(CouponTarget::getCouponId, coupon.getId()));

            if (coupon.getType() == CouponTypes.FREE_FREIGHT.getCode()) {
                if (out.freight > 0) {
                    throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "免邮券只能使用一张");
                }
                long freight = Math.max(0L, cmd.getFreightFen() == null ? 0L : cmd.getFreightFen());
                long deduct = coupon.getFaceValueFen() != null && coupon.getFaceValueFen() > 0
                        ? Math.min(freight, coupon.getFaceValueFen()) : freight;
                out.freight = deduct;
                out.usedIds.add(id);
                continue;
            }

            List<Integer> matched = matchedLines(lines, coupon, targets);
            if (matched.isEmpty()) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不适用当前商品");
            }
            long subtotal = 0L;
            for (int i : matched) {
                subtotal += lines.get(i).getCur();
            }
            long threshold = coupon.getThresholdFen() == null ? 0L : coupon.getThresholdFen();
            if (subtotal < threshold) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "未达到优惠券使用门槛");
            }
            long discount;
            if (coupon.getType() == CouponTypes.DISCOUNT.getCode()) {
                discount = bpDiscount(subtotal, coupon.getDiscountBp() == null ? 1000 : coupon.getDiscountBp());
                if (coupon.getMaxDiscountFen() != null && coupon.getMaxDiscountFen() > 0) {
                    discount = Math.min(discount, coupon.getMaxDiscountFen());
                }
            } else {
                discount = coupon.getFaceValueFen() == null ? 0L : coupon.getFaceValueFen();
            }
            discount = Math.max(0L, Math.min(discount, subtotal));
            if (discount <= 0) {
                continue;
            }
            distribute(lines, matched, discount, false);
            if (slot == 0) {
                out.category = discount;
            } else if (slot == 1) {
                out.shop = discount;
            } else {
                out.platform = discount;
            }
            out.usedIds.add(id);
        }
        return out;
    }

    private List<Integer> matchedLines(List<CalcLine> lines, Coupon coupon, List<CouponTarget> targets) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            CalcItem item = lines.get(i).getItem();
            boolean hit;
            switch (coupon.getScopeType() == null ? 1 : coupon.getScopeType()) {
                case 2 -> hit = targets.stream().anyMatch(t -> t.getTargetType() == 1
                        && Objects.equals(t.getTargetId(), item.getSkuId()));
                case 3 -> hit = targets.stream().anyMatch(t -> t.getTargetType() == 2
                        && Objects.equals(t.getTargetId(), item.getSpuId()));
                case 4 -> hit = targets.stream().anyMatch(t -> t.getTargetType() == 3
                        && Objects.equals(t.getTargetId(), item.getCategory3Id()));
                case 5 -> hit = Objects.equals(coupon.getShopId(), item.getShopId());
                default -> hit = true;
            }
            if (hit) {
                matched.add(i);
            }
        }
        return matched;
    }

    // ------------------------------------------------------------------
    // 积分层：100 积分=1 元（上送金额已换算为分），最高抵商品金额 50%
    // ------------------------------------------------------------------
    private long applyPoints(List<CalcLine> lines, long wantPoints) {
        long productTotal = lines.stream().mapToLong(CalcLine::getCur).sum();
        long deduct = Math.min(wantPoints, productTotal / 2);
        if (deduct <= 0) {
            return 0L;
        }
        List<Long> weights = lines.stream().map(l -> l.getCur() > 0 ? l.getCur() : 0L).toList();
        List<Long> allocs = MoneyUtils.allocate(deduct, weights);
        for (int i = 0; i < lines.size(); i++) {
            long a = allocs.get(i);
            lines.get(i).setPointsAlloc(a);
            lines.get(i).setCur(lines.get(i).getCur() - a);
        }
        return deduct;
    }

    /** 运费按最终商品金额占比分摊（兜底等额分摊）。 */
    private void applyFreight(List<CalcLine> lines, long freight) {
        if (freight <= 0) {
            return;
        }
        List<Long> weights = lines.stream().map(l -> l.getCur() > 0 ? l.getCur() : 0L).toList();
        if (weights.stream().mapToLong(Long::longValue).sum() <= 0) {
            weights = lines.stream().map(l -> l.getOriginal() > 0 ? l.getOriginal() : 0L).toList();
        }
        if (weights.stream().mapToLong(Long::longValue).sum() <= 0) {
            weights = lines.stream().map(l -> 1L).toList();
        }
        List<Long> allocs = MoneyUtils.allocate(freight, weights);
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).setFreightAlloc(allocs.get(i));
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 将 total 按各行当前金额权重（最大余数法）分摊到指定行，写 shopPromo/coupon 列。 */
    private long distribute(List<CalcLine> lines, List<Integer> idx, long total, boolean shopLayer) {
        if (idx == null || idx.isEmpty() || total <= 0) {
            return 0L;
        }
        List<Long> weights = new ArrayList<>(Collections.nCopies(lines.size(), 0L));
        long wsum = 0L;
        for (int i : idx) {
            long w = Math.max(0L, lines.get(i).getCur());
            weights.set(i, w);
            wsum += w;
        }
        long capped = Math.min(total, wsum);
        List<Long> all = MoneyUtils.allocate(capped, weights);
        for (int i = 0; i < lines.size(); i++) {
            long a = all.get(i);
            if (a == 0) {
                continue;
            }
            CalcLine line = lines.get(i);
            if (shopLayer) {
                line.setShopPromoAlloc(line.getShopPromoAlloc() + a);
            } else {
                line.setCouponAlloc(line.getCouponAlloc() + a);
            }
            line.setCur(line.getCur() - a);
        }
        return capped;
    }

    private boolean matches(Promo p, List<PromoTarget> targets, CalcItem item) {
        int scope = p.getScopeType() == null ? 1 : p.getScopeType();
        if (scope == 1) {
            return true;
        }
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        return targets.stream().anyMatch(t -> switch (t.getTargetType()) {
            case 1 -> Objects.equals(t.getTargetId(), item.getSkuId());
            case 2 -> Objects.equals(t.getTargetId(), item.getSpuId());
            case 3 -> Objects.equals(t.getTargetId(), item.getCategory3Id());
            default -> false;
        });
    }

    /** 多级门槛：取 threshold ≤ subtotal 的最高门槛档位。 */
    private PromoLevel bestLevel(List<PromoLevel> levels, long subtotal) {
        PromoLevel best = null;
        for (PromoLevel lv : levels) {
            long threshold = lv.getThresholdFen() == null ? 0L : lv.getThresholdFen();
            if (subtotal >= threshold && (best == null || threshold > best.getThresholdFen())) {
                best = lv;
            }
        }
        return best;
    }

    /** 二次校验促销时间窗（查询服务已过滤，引擎侧防御，保证试算绝不下发未开始/已结束活动）。 */
    private List<Promo> filterInWindow(List<Promo> promos, LocalDateTime now) {
        List<Promo> result = new ArrayList<>(promos.size());
        for (Promo p : promos) {
            if (p.getStartTime() != null && now.isBefore(p.getStartTime())) {
                continue;
            }
            if (p.getEndTime() != null && now.isAfter(p.getEndTime())) {
                continue;
            }
            result.add(p);
        }
        return result;
    }

    private List<Promo> filter(List<Promo> promos, int type) {        List<Promo> result = new ArrayList<>();
        for (Promo p : promos) {
            if (p.getType() == type) {
                result.add(p);
            }
        }
        return result;
    }

    private List<Long> promoIds(List<Promo> promos) {
        List<Long> ids = new ArrayList<>(promos.size());
        for (Promo p : promos) {
            ids.add(p.getId());
        }
        return ids;
    }

    /** 按基点折扣计算优惠金额（HALF_UP，分）。 */
    public static long bpDiscount(long amount, int bp) {
        if (amount <= 0 || bp >= 1000) {
            return 0L;
        }
        long paid = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bp))
                .divide(BigDecimal.valueOf(1000), RoundingMode.HALF_UP).longValueExact();
        return amount - paid;
    }

    private PriceCalcResult buildResult(List<CalcLine> lines, long productPromo, long shopPromo,
                                        long categoryCoupon, long shopCoupon, long platformCoupon,
                                        long freightCoupon, long points, long finalFreight,
                                        LinkedHashMap<Long, Integer> gifts) {
        long original = lines.stream().mapToLong(CalcLine::getOriginal).sum();
        List<ItemPriceDetail> details = new ArrayList<>(lines.size() + gifts.size());
        for (CalcLine line : lines) {
            long paid = line.getOriginal() - line.getProductPromoAlloc() - line.getShopPromoAlloc()
                    - line.getCouponAlloc() - line.getPointsAlloc() + line.getFreightAlloc();
            details.add(ItemPriceDetail.builder()
                    .skuId(line.getItem().getSkuId())
                    .qty(line.getItem().getQty())
                    .originalFen(line.getOriginal())
                    .productPromoFen(line.getProductPromoAlloc())
                    .shopPromoFen(line.getShopPromoAlloc())
                    .couponAllocFen(line.getCouponAlloc())
                    .pointsAllocFen(line.getPointsAlloc())
                    .freightAllocFen(line.getFreightAlloc())
                    .paidFen(Math.max(0L, paid))
                    .giftFlag(0)
                    .build());
        }
        for (Map.Entry<Long, Integer> gift : gifts.entrySet()) {
            details.add(ItemPriceDetail.builder()
                    .skuId(gift.getKey()).qty(gift.getValue())
                    .originalFen(0L).productPromoFen(0L).shopPromoFen(0L).couponAllocFen(0L)
                    .pointsAllocFen(0L).freightAllocFen(0L).paidFen(0L).giftFlag(1).build());
        }
        long couponSum = categoryCoupon + shopCoupon + platformCoupon;
        long pay = original - productPromo - shopPromo - couponSum - points + finalFreight;
        return PriceCalcResult.builder()
                .originalProductFen(original)
                .productPromoFen(productPromo)
                .shopPromoFen(shopPromo)
                .categoryCouponFen(categoryCoupon)
                .shopCouponFen(shopCoupon)
                .platformCouponFen(platformCoupon)
                .freightCouponFen(freightCoupon)
                .pointsDeductFen(points)
                .freightFen(finalFreight)
                .payFen(Math.max(0L, pay))
                .itemDetails(details)
                .usedUserCouponIds(new ArrayList<>())
                .giftSkuIds(new ArrayList<>(gifts.keySet()))
                .build();
    }

    private void assertConservation(PriceCalcResult r, long rawFreight) {
        long sumOriginal = r.getItemDetails().stream()
                .filter(d -> d.getGiftFlag() == null || d.getGiftFlag() == 0)
                .mapToLong(ItemPriceDetail::getOriginalFen).sum();
        long sumPaid = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getPaidFen).sum();
        long sumProductPromo = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getProductPromoFen).sum();
        long sumShopPromo = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getShopPromoFen).sum();
        long sumCoupon = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getCouponAllocFen).sum();
        long sumPoints = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getPointsAllocFen).sum();
        long sumFreight = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getFreightAllocFen).sum();
        if (sumOriginal != r.getOriginalProductFen()
                || sumProductPromo != r.getProductPromoFen()
                || sumShopPromo != r.getShopPromoFen()
                || sumCoupon != r.getCategoryCouponFen() + r.getShopCouponFen() + r.getPlatformCouponFen()
                || sumPoints != r.getPointsDeductFen()
                || sumFreight != r.getFreightFen()
                || sumPaid != r.getPayFen()) {
            throw new IllegalStateException("优惠分摊不守恒：result=" + JsonUtils.toJson(r));
        }
    }
}
