package com.shop.settlement.withdraw.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 自动提现配置请求（每日/每周，design 7.4）。 */
@Data
public class AutoWithdrawConfigRequest {

    @NotNull(message = "开启状态不能为空")
    private Integer enabled;

    @NotNull(message = "提现频率不能为空")
    private Integer frequency;

    /** frequency=2 每周时必填：1 周一 ~ 7 周日 */
    @Min(value = 1, message = "星期取值1~7")
    @Max(value = 7, message = "星期取值1~7")
    private Integer weekday;

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
}
