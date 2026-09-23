package com.shop.user.address.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 收货地址（t_user_address）：每用户最多 20 条，仅 1 个默认地址。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_address")
public class UserAddress extends BaseEntity {

    private Long userId;

    private String receiver;

    private String phone;

    private String province;

    private String city;

    private String district;

    private String detailAddress;

    private String zipCode;

    /** 家/公司/学校/其他 */
    private String tag;

    /** 0 否 1 是（每用户至多 1 个） */
    private Integer isDefault;

    @Version
    private Integer version;
}
