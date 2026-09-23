package com.shop.api.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 营销资源确认命令（支付成功，对应 ORDER_PAID 对营销的消费语义）。
 *
 * <p>将 orderNo 对应的预核销券转为已使用、秒杀锁定库存转为扣减；以 orderNo 幂等。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionConfirmCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "userId 不能为空")
    private Long userId;

    /** 订单号 */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;
}
