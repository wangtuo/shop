package com.shop.order.support;

import com.shop.api.user.dto.AddressDTO;
import org.springframework.stereotype.Component;

/**
 * 区域配送校验（design 5.3.1 第 5 项）。
 *
 * <p>当前平台默认全国可配送：只要地址归属用户且省市区信息完整即通过。
 * 后续接入区域配送规则表/禁售地址时仅需扩展本类（校验点已在下单链路固定）。
 */
@Component
public class RegionDeliveryChecker {

    public void check(AddressDTO address, Long userId) {
        if (address == null) {
            throw new com.shop.common.exception.BizException(
                    com.shop.common.exception.ErrorCode.NOT_FOUND, "收货地址不存在");
        }
        if (address.getUserId() == null || !address.getUserId().equals(userId)) {
            throw new com.shop.common.exception.BizException(
                    com.shop.common.exception.ErrorCode.FORBIDDEN, "收货地址不属于当前用户");
        }
        if (isBlank(address.getProvince()) || isBlank(address.getReceiver())
                || isBlank(address.getDetailAddress())) {
            throw new com.shop.common.exception.BizException(
                    com.shop.common.exception.ErrorCode.PARAM_INVALID, "收货地址信息不完整，无法配送");
        }
        // 校验点：未来在此读取区域配送规则表，不支持区域抛 AFTERSALE... 业务错误码
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
