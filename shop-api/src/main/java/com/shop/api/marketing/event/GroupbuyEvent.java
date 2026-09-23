package com.shop.api.marketing.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 拼团事件（Topic：GROUPBUY_EVENT，CONTRACTS.md §5）。
 *
 * <p>驱动团状态推进：1 开团（团长）/ 2 参团（团员，同一用户同一活动仅 1 次）/
 * 3 成团（人数达标，团员订单转待发货）/ 4 失败（24h 超时人数不足，自动退款）。
 * 消费端以 eventId/groupNo+orderNo 幂等。拼团订单不可用券、不可用积分（design.md 4.2.3、4.4）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class GroupbuyEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 拼团活动 ID */
    private Long activityId;

    /** 团号（同一团的开团/参团/成团/失败事件共用） */
    private String groupNo;

    /** 用户 ID（团长或团员） */
    private Long userId;

    /** 关联订单号（幂等键） */
    private String orderNo;

    /** 操作类型：1 开团 2 参团 3 成团 4 失败（GroupbuyOpType） */
    private Integer type;

    /** 成团所需人数：2/3/5/10 */
    private Integer requiredPeople;

    /** 团长标记：1 团长 0 团员（生产端从 GroupbuyMember.leaderFlag 透传） */
    private Integer leaderFlag;
}
