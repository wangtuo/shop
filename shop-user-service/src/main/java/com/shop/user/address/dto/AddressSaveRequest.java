package com.shop.user.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/修改收货地址请求。每用户最多 20 条；字段来源 design.md 2.3。
 */
@Data
public class AddressSaveRequest {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 64, message = "收货人长度不能超过64")
    private String receiver;

    @NotBlank(message = "收货人手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "省不能为空")
    private String province;

    @NotBlank(message = "市不能为空")
    private String city;

    @NotBlank(message = "区/县不能为空")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 256, message = "详细地址长度不能超过256")
    private String detailAddress;

    /** 邮编（可选） */
    private String zipCode;

    /** 标签：家/公司/学校/其他 */
    private String tag;

    /** 是否默认：0 否 1 是；新增的第一条地址强制为默认 */
    private Integer isDefault;
}
