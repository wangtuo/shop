package com.shop.product.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;

/**
 * 商品域接口鉴权工具：平台运营（userType=2）可写类目/品牌/审核/处罚；
 * 商户（userType=1）只能操作本店数据。
 */
public final class AuthUtils {

    /** 平台运营用户类型 */
    public static final int USER_TYPE_PLATFORM = 2;
    /** 商户用户类型 */
    public static final int USER_TYPE_MERCHANT = 1;

    private AuthUtils() {
    }

    /** 要求当前登录人是平台运营，否则抛 FORBIDDEN。 */
    public static LoginUser requirePlatform() {
        LoginUser user = UserContext.get();
        if (user.getUserType() == null || user.getUserType() != USER_TYPE_PLATFORM) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台运营可操作");
        }
        return user;
    }

    /** 要求当前登录人是商户并返回其 merchantId，否则抛 FORBIDDEN。 */
    public static long requireMerchantId() {
        LoginUser user = UserContext.get();
        Long merchantId = user.getMerchantId();
        if (user.getUserType() == null || user.getUserType() != USER_TYPE_MERCHANT || merchantId == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅商户可操作");
        }
        return merchantId;
    }

    /** 校验数据归属：当前商户只能操作本店数据。 */
    public static void checkOwner(Long ownerMerchantId) {
        long current = requireMerchantId();
        if (ownerMerchantId == null || ownerMerchantId != current) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作非本店商品");
        }
    }
}
