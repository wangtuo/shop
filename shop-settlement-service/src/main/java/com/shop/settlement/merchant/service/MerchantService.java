package com.shop.settlement.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.MerchantLevels;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商户档案服务：入驻、等级、查询。
 */
@Service
@RequiredArgsConstructor
public class MerchantService {

    /** 保证金按类目 1000 元 ~ 50000 元（分）。 */
    public static final long DEPOSIT_MIN_FEN = 100_000L;
    public static final long DEPOSIT_MAX_FEN = 5_000_000L;

    private final MerchantMapper merchantMapper;
    private final AccountService accountService;

    /** 商户入驻：登记类目默认佣金率与应缴保证金（design 7.6，1000~50000 元按类目）。 */
    @Transactional
    public SettMerchant onboard(Long merchantId, String merchantName, Long categoryId, String categoryName,
                                Integer commissionRateBps, Long depositRequiredFen) {
        if (merchantId == null || merchantId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商户ID非法");
        }
        if (commissionRateBps == null || commissionRateBps < 0 || commissionRateBps > 10000) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类目佣金率必须在0~10000bps之间");
        }
        if (depositRequiredFen == null || depositRequiredFen < DEPOSIT_MIN_FEN
                || depositRequiredFen > DEPOSIT_MAX_FEN) {
            throw new BizException(ErrorCode.PARAM_INVALID, "应缴保证金必须在1000~50000元之间（按类目）");
        }
        if (merchantMapper.selectById(merchantId) != null) {
            throw new BizException(ErrorCode.CONFLICT, "商户已入驻: " + merchantId);
        }
        SettMerchant merchant = new SettMerchant();
        merchant.setId(merchantId);
        merchant.setMerchantName(merchantName == null ? "" : merchantName);
        merchant.setMerchantLevel(MerchantLevels.C);
        merchant.setCategoryId(categoryId == null ? 0L : categoryId);
        merchant.setCategoryName(categoryName == null ? "" : categoryName);
        merchant.setCommissionRateBps(commissionRateBps);
        merchant.setDepositBalanceFen(0L);
        merchant.setDepositRequiredFen(depositRequiredFen);
        merchant.setDepositAlerted(0);
        merchant.setStatus(com.shop.settlement.enums.MerchantStatuses.NORMAL);
        merchantMapper.insert(merchant);
        // 自动开立商户结算账户
        accountService.getOrCreate(merchantId, AccountRole.MERCHANT);
        return merchant;
    }

    /** 调整商户等级（平台运营）。 */
    @Transactional
    public void updateLevel(long merchantId, int level) {
        if (level != MerchantLevels.S && level != MerchantLevels.A
                && level != MerchantLevels.B && level != MerchantLevels.C) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知商户等级: " + level);
        }
        SettMerchant merchant = requireMerchant(merchantId);
        merchant.setMerchantLevel(level);
        merchantMapper.updateById(merchant);
    }

    /** 查商户，不存在抛 NOT_FOUND。 */
    public SettMerchant requireMerchant(long merchantId) {
        SettMerchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商户不存在: " + merchantId);
        }
        return merchant;
    }

    public SettMerchant findByMerchantId(long merchantId) {
        return merchantMapper.selectById(merchantId);
    }

    /** 校验商户处于可经营状态。 */
    public SettMerchant requireActiveMerchant(long merchantId) {
        SettMerchant merchant = requireMerchant(merchantId);
        if (merchant.getStatus() == null
                || merchant.getStatus() == com.shop.settlement.enums.MerchantStatuses.DISABLED
                || merchant.getStatus() == com.shop.settlement.enums.MerchantStatuses.RESIGNED) {
            throw new BizException(ErrorCode.FORBIDDEN, "商户已禁用或清退，不可操作");
        }
        return merchant;
    }

    /** 按状态查询（清退扫描用）。 */
    public java.util.List<SettMerchant> listByStatus(int status) {
        return merchantMapper.selectList(new LambdaQueryWrapper<SettMerchant>()
                .eq(SettMerchant::getStatus, status));
    }
}
