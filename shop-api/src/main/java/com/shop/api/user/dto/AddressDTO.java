package com.shop.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 收货地址 DTO。每个用户最多 20 个地址，仅 1 个默认地址。
 *
 * <p>规则来源：design.md 2.3 收货地址。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddressDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 地址 ID */
    private Long addressId;

    /** 所属用户 ID */
    private Long userId;

    /** 收货人姓名 */
    private String receiver;

    /** 收货人手机号 */
    private String phone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String detailAddress;

    /** 邮政编码 */
    private String zipCode;

    /** 地址标签：家/公司/学校/其他 */
    private String tag;

    /** 是否默认地址：0 否 1 是 */
    private Integer isDefault;
}
