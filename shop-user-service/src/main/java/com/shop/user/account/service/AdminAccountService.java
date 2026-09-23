package com.shop.user.account.service;

import com.shop.user.account.dto.AdminCreateAccountRequest;

/**
 * 平台运营账号管理：C-2 安全修复的配套正规通道。
 * 商户账号不再允许自助注册，改由平台运营在入驻审核环节服务端开通。
 */
public interface AdminAccountService {

    /**
     * 开通商户登录账号（userType=1，merchantId=新账号自身 ID），
     * 随后清算域以该 ID 作为 merchantId 完成商户入驻。
     *
     * @return 新商户用户 ID（即后续入驻用的 merchantId）
     */
    Long createMerchantAccount(AdminCreateAccountRequest request);

    /**
     * 新增平台运营账号（userType=2）。首个平台账号由一次性引导接口创建，
     * 后续运营账号由在岗平台运营通过本方法开通。
     *
     * @return 新平台运营用户 ID
     */
    Long createPlatformAccount(AdminCreateAccountRequest request);
}
