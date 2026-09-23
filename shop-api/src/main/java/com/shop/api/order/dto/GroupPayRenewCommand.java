package com.shop.api.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 拼团支付截止时间续期命令（MARKETING C25，TRADE 半卡）。
 *
 * <p>成团后给未付款团员延长支付窗口：仅 status=10 待付款且已挂成团标记可续，
 * CAS 写 expire_time 并重投 ORDER_PAY_TIMEOUT 延时消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupPayRenewCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 团号 */
    @NotBlank(message = "groupNo 不能为空")
    private String groupNo;

    /** 订单号 */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 延长秒数（默认 1800=30 分钟） */
    private Long plusSeconds;
}
