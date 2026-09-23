package com.shop.api.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 营销资源释放命令（订单取消/超时未付，对应 ORDER_CANCELLED 对营销的消费语义）。
 *
 * <p>将 orderNo 对应的预核销券退回未使用、秒杀锁定库存释放回可售；以 orderNo 幂等，
 * 未找到锁定记录（已确认/已释放）也按成功返回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionReleaseCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "userId 不能为空")
    private Long userId;

    /** 订单号 */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;
}
