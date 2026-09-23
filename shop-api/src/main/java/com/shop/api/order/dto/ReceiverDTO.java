package com.shop.api.order.dto;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shop.common.jackson.JsonViews;
import com.shop.common.jackson.PhoneMaskingSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 收货信息 DTO（design.md 5.1.1，下单时地址快照）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiverDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 收货人姓名 */
    private String receiver;

    /**
     * 收货人手机号。对外默认脱敏（前 3 后 4）；内部 Feign /inner/** 经 Internal 视图取明文。
     */
    @JsonView(JsonViews.Internal.class)
    @JsonSerialize(using = PhoneMaskingSerializer.class)
    private String phone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String detailAddress;
}
