package com.shop.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 用户会员等级信息 DTO。
 *
 * <p>等级与权益：
 * <ul>
 *   <li>L0 新会员：成长值 0-99，折扣 1.00，积分倍率 1</li>
 *   <li>L1 银卡：100-999，0.98，1.1</li>
 *   <li>L2 金卡：1000-4999，0.95，1.5</li>
 *   <li>L3 白金：5000-19999，0.92，2</li>
 *   <li>L4 钻石：20000+，0.90，3</li>
 * </ul>
 *
 * <p>规则来源：design.md 2.1.2 用户等级体系、CONTRACTS.md §4。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLevelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 会员等级：L0=0 ... L4=4 */
    private Integer level;

    /** 等级名称：新会员/银卡会员/金卡会员/白金会员/钻石会员 */
    private String levelName;

    /** 当前成长值 */
    private Long growth;

    /** 等级折扣，如 0.98 表示 9.8 折；1.00 表示无折扣 */
    private BigDecimal discount;

    /** 积分发放倍率，如 1.1 表示消费所得积分 ×1.1 */
    private BigDecimal pointsRate;
}
