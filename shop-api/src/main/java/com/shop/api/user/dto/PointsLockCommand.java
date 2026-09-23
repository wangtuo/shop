package com.shop.api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 积分预扣（冻结）命令：下单时冻结积分，订单取消时释放、支付成功时实扣。
 *
 * <p>换算规则：100 积分 = 100 分 = 1 元；单笔订单积分抵现最高不超过订单金额的 50%。
 *
 * <p>规则来源：design.md 2.2.2 积分规则、CONTRACTS.md §5 下单关键链路。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointsLockCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 业务单号（订单号），幂等键 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 64, message = "业务单号长度不能超过 64")
    private String bizNo;

    /** 冻结积分个数（无积分下单允许 0） */
    @NotNull(message = "积分数量不能为空")
    @PositiveOrZero(message = "积分数量不能为负数")
    private Long points;

    /** 积分抵现金额，单位：分（100 积分 = 100 分 = 1 元；无积分下单允许 0） */
    @NotNull(message = "抵现金额不能为空")
    @PositiveOrZero(message = "抵现金额不能为负数")
    private Long deductFen;

    /** 积分场景，取值见 {@code PointsScene}（下单抵现为消费场景） */
    private Integer scene;
}
