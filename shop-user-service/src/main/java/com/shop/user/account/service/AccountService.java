package com.shop.user.account.service;

import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.common.result.PageResult;
import com.shop.user.account.dto.PointsAccountVO;
import com.shop.user.account.entity.UserAccountFlow;
import com.shop.common.result.PageQuery;

import java.time.LocalDateTime;

/**
 * 用户账户服务：余额/赠金借/贷、积分冻结/实扣/释放/退回/发放/过期清零。
 * 全部写操作以 bizNo(+changeType) 幂等，余额/积分一律条件更新防透支。
 */
public interface AccountService {

    /** 新用户注册后初始化三个账户（余额/赠金/积分，余额均为 0），重复初始化幂等 */
    void initAccounts(Long userId);

    /** 资金账户借（扣减）：accountType 1 余额 2 赠金 */
    void debitMoney(AmountCommand cmd, int accountType);

    /** 余额贷（退款入账） */
    void creditMoney(AmountCommand cmd);

    /** 下单冻结积分（TCC-try） */
    void lockPoints(PointsLockCommand cmd);

    /** 支付成功实扣冻结积分（TCC-confirm），FIFO 冲减获取批次 */
    void deductPoints(PointsDeductCommand cmd);

    /** 订单取消释放冻结积分（TCC-cancel） */
    void releasePoints(PointsReleaseCommand cmd);

    /** 退款按比例退回积分（生成新批次，365 天有效） */
    void refundPoints(PointsRefundCommand cmd);

    /**
     * 发放积分（消费/签到/评价/分享/晒单/补偿），执行每日上限裁剪与 365 天批次入账。
     *
     * @return 实际发放积分（受每日上限裁剪后可能小于申请值，超额为 0）
     */
    long grantPoints(GrantPointsCommand cmd);

    /** 积分账户视图 */
    PointsAccountVO getPointsAccount(Long userId);

    /** 账户流水分页（accountType 1/2/3） */
    PageResult<UserAccountFlow> pageFlows(Long userId, int accountType, PageQuery query);

    /**
     * 扫描并清零已过期（365 天）积分批次，发 POINTS_CHANGED。
     *
     * @param now 当前时间（定时任务注入，单测可固定时钟）
     * @return 被清零的批次数
     */
    int expireDuePoints(LocalDateTime now);
}
