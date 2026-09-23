package com.shop.api.marketing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 营销价格试算命令（确认订单页/下单前）。
 *
 * <p>orderType：1 普通 2 秒杀 3 拼团 4 预售 5 换货（CONTRACTS.md §4）。
 *
 * <p>互斥规则（design.md 4.2.3）由营销域据此执行：
 * <ul>
 *   <li>orderType=2 秒杀：秒杀价与所有其他优惠（满减/券/积分/免邮）互斥，仅计运费；</li>
 *   <li>orderType=3 拼团：不可用任何优惠券、不可用积分抵现；</li>
 *   <li>orderType=4 预售：券仅在尾款阶段可用（{@code presaleFinalStage=true}），定金阶段不可用；</li>
 *   <li>品类券/店铺券/平台券各最多 1 张（同层 1 张）；满减与满折互斥取优惠最大者。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceCalcCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（游客可为空） */
    private Long userId;

    /** 会员等级：L0=0 ... L4=4（CONTRACTS.md §4，等级折扣 1.0/0.98/0.95/0.92/0.90） */
    @NotNull(message = "userLevel 不能为空")
    private Integer userLevel;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    @NotNull(message = "orderType 不能为空")
    private Integer orderType;

    /** 试算商品行 */
    @NotEmpty(message = "items 不能为空")
    @Valid
    @Builder.Default
    private List<CalcItem> items = new ArrayList<>();

    /** 订单原始运费（分），免邮券作用于该金额 */
    @NotNull(message = "freightFen 不能为空")
    private Long freightFen;

    /** 拟使用积分抵现金额（分；100 积分=1 元，单笔最高抵商品金额 50%；拼团不可用） */
    private Long usePointsFen;

    /** 用户选用的品类券 ID（用户券记录 ID） */
    private Long categoryCouponId;

    /** 用户选用的店铺券 ID（用户券记录 ID） */
    private Long shopCouponId;

    /** 用户选用的平台通用券 ID（用户券记录 ID） */
    private Long platformCouponId;

    /** 秒杀活动 ID（orderType=2 时必填） */
    private Long seckillActivityId;

    /** 拼团活动 ID（orderType=3 时必填） */
    private Long groupbuyActivityId;

    /** 预售活动 ID（orderType=4 时必填） */
    private Long presaleActivityId;

    /** 预售尾款阶段标记：true=尾款阶段（券只在尾款可用），false/null=定金阶段（券不可用） */
    private Boolean presaleFinalStage;

    /** 团长标记：1 团长 0 团员（拼团试算时上送，团长价口径）；null 按非团长处理 */
    private Integer leaderFlag;

    /** 拼团团号（拼团续算/成团后支付场景上送），普通试算为空 */
    private String groupNo;
}
