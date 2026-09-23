package com.shop.marketing.engine;

import com.shop.api.marketing.dto.CalcItem;
import lombok.Getter;
import lombok.Setter;

/**
 * 试算引擎内部行状态：cur 为经过各层优惠后的本行剩余商品金额（分），
 * 各层 promo*Alloc 记录本行在该层被分摊的优惠金额。
 */
@Getter
@Setter
public class CalcLine {

    private final CalcItem item;
    /** 原价小计 salePriceFen × qty */
    private final long original;
    /** 当前剩余商品金额（每层扣减后更新） */
    private long cur;
    private long productPromoAlloc;
    private long shopPromoAlloc;
    private long couponAlloc;
    private long pointsAlloc;
    private long freightAlloc;
    /**
     * 活动价快照锁定（B1 团长价/B3 砍价成交价等 activityPriceFen 驱动的行）：
     * 为 true 时跳过会员折扣/限时折扣等商品层常规促销（活动成交价不再与商品级促销叠加，
     * 与秒杀/拼团互斥口径一致）；店铺层、券、积分仍按既有开关作用。
     */
    private boolean priceLocked;

    public CalcLine(CalcItem item) {
        this.item = item;
        this.original = item.getSalePriceFen() * item.getQty();
        this.cur = this.original;
    }

    public long weight() {
        return cur > 0 ? cur : 0L;
    }
}
