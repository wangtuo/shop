package com.shop.product.goods.statemachine;

import com.shop.api.product.enums.GoodsStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 商品八态状态机（design 3.2），无状态独立组件。
 *
 * <pre>
 * 0 草稿 ──提交──▶ 1 待审核 ──通过──▶ 3 已上架
 *  ▲                │──拒绝──▶ 2 审核拒绝 ──修改──▶ 0 草稿
 *  └──── 修改 ◀──────┘           └──重新提交──▶ 1
 * 3 已上架 ──手动下架──▶ 4 已下架 ──重新上架──▶ 3
 * 3 已上架 ──库存为0──▶ 5 售罄 ──补货──▶ 3 已上架（库存驱动，条件更新）
 * 1/3/4/5 ──平台处罚──▶ 6 违规下架（终态，不允许再上架）
 * 0/2/3/4/5 ──删除──▶ 7 已删除（终态，逻辑删除）
 * </pre>
 * <p>非法跳转抛 {@link BizException}；DB 侧还需带 status 条件更新做并发兜底。</p>
 */
@Component
public class GoodsStateMachine {

    private static final Map<GoodsStatuses, Set<GoodsStatuses>> ALLOWED = new EnumMap<>(GoodsStatuses.class);

    static {
        // 草稿：提交审核 / 删除
        ALLOWED.put(GoodsStatuses.DRAFT, EnumSet.of(GoodsStatuses.PENDING_AUDIT, GoodsStatuses.DELETED));
        // 待审核：通过 / 拒绝 / 违规下架
        ALLOWED.put(GoodsStatuses.PENDING_AUDIT,
                EnumSet.of(GoodsStatuses.ON_SALE, GoodsStatuses.AUDIT_REJECT, GoodsStatuses.VIOLATION_OFF));
        // 审核拒绝：修改回草稿 / 重新提交 / 删除
        ALLOWED.put(GoodsStatuses.AUDIT_REJECT,
                EnumSet.of(GoodsStatuses.DRAFT, GoodsStatuses.PENDING_AUDIT, GoodsStatuses.DELETED));
        // 已上架：手动下架 / 库存售罄 / 违规下架 / 删除
        ALLOWED.put(GoodsStatuses.ON_SALE, EnumSet.of(
                GoodsStatuses.OFF_SALE, GoodsStatuses.SOLD_OUT, GoodsStatuses.VIOLATION_OFF, GoodsStatuses.DELETED));
        // 已下架：重新上架 / 违规下架 / 删除
        ALLOWED.put(GoodsStatuses.OFF_SALE,
                EnumSet.of(GoodsStatuses.ON_SALE, GoodsStatuses.VIOLATION_OFF, GoodsStatuses.DELETED));
        // 售罄：补货自动上架 / 手动下架 / 违规下架 / 删除
        ALLOWED.put(GoodsStatuses.SOLD_OUT,
                EnumSet.of(GoodsStatuses.ON_SALE, GoodsStatuses.OFF_SALE,
                        GoodsStatuses.VIOLATION_OFF, GoodsStatuses.DELETED));
        // 违规下架 / 已删除：终态
        ALLOWED.put(GoodsStatuses.VIOLATION_OFF, EnumSet.noneOf(GoodsStatuses.class));
        ALLOWED.put(GoodsStatuses.DELETED, EnumSet.noneOf(GoodsStatuses.class));
    }

    /**
     * 校验状态流转是否合法，非法抛 BizException(CONFLICT)。
     */
    public void checkTransition(int fromCode, int toCode) {
        GoodsStatuses from = GoodsStatuses.fromCode(fromCode);
        GoodsStatuses to = GoodsStatuses.fromCode(toCode);
        if (from == to) {
            throw new BizException(ErrorCode.CONFLICT, "商品状态已处于" + to.name());
        }
        if (!ALLOWED.getOrDefault(from, EnumSet.noneOf(GoodsStatuses.class)).contains(to)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "商品状态不允许该操作：" + from.getCode() + "(" + from.name() + ") → "
                            + to.getCode() + "(" + to.name() + ")");
        }
    }

    /** 判定流转是否合法（单测/条件分支使用）。 */
    public boolean canTransit(int fromCode, int toCode) {
        try {
            GoodsStatuses from = GoodsStatuses.fromCode(fromCode);
            GoodsStatuses to = GoodsStatuses.fromCode(toCode);
            return from != to && ALLOWED.getOrDefault(from, EnumSet.noneOf(GoodsStatuses.class)).contains(to);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
