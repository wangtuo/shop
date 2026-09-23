package com.shop.settlement.withdraw.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 提现申请请求（design 7.4）。 */
@Data
public class ApplyWithdrawRequest {

    @NotNull(message = "提现金额不能为空")
    @Min(value = 10000, message = "提现金额不能低于100元")
    private Long amountFen;

    @NotNull(message = "提现渠道不能为空")
    private Integer channel;

    @NotBlank(message = "收款账号不能为空")
    @Size(max = 128)
    private String channelAccount;

    @NotBlank(message = "收款人姓名不能为空")
    @Size(max = 64)
    private String accountName;

    @Size(max = 64)
    private String bankName;

    /**
     * 客户端幂等令牌（M-3，可选）：同商户同令牌窗口内拒绝重复提交；
     * 缺省时服务端以预生成的 withdrawNo 兜底。
     */
    @Size(max = 64)
    private String clientToken;

    /**
     * 提现单号（M-3）：仅由服务端在进入业务前预生成并覆盖，body 传入值一律不信任。
     */
    private String withdrawNo;
}
