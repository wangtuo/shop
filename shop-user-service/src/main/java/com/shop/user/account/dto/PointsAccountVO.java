package com.shop.user.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 积分账户视图：可用积分 + 冻结积分。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointsAccountVO {

    /** 可用积分 */
    private Long balance;

    /** 冻结积分（下单预抵未确认） */
    private Long frozen;
}
