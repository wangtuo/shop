package com.shop.marketing.activity.bargain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.activity.bargain.entity.BargainHelp;
import com.shop.marketing.activity.bargain.mapper.BargainHelpMapper;
import com.shop.marketing.activity.entity.BargainRecord;
import com.shop.marketing.activity.mapper.BargainRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 砍价写库事务边界（必须经 Spring 代理调用；由 {@link BargainService} 在分布式锁内调用，
 * 返回时事务已提交，外层随后才释放锁）。
 */
@Component
@RequiredArgsConstructor
public class BargainTxOps {

    private final BargainRecordMapper recordMapper;
    private final BargainHelpMapper helpMapper;

    /**
     * 同一事务内完成：重复帮砍校验（uk 兜底）→ 记录乐观锁 CAS（价/次数/版本）→ 帮砍留痕插入。
     * 任一步失败整体回滚，不会出现"价砍了但留痕缺失"或反之。
     *
     * @param record   锁内重读的当前记录
     * @param help     待插入留痕（cutFen 已按剩余可砍金额收敛）
     * @param newPrice 砍后价（&gt;= floor）
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyCut(BargainRecord record, BargainHelp help, long newPrice) {
        Long exists = helpMapper.selectCount(new LambdaQueryWrapper<BargainHelp>()
                .eq(BargainHelp::getRecordId, record.getId())
                .eq(BargainHelp::getHelperUserId, help.getHelperUserId()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.CONFLICT, "您已帮砍过，不能重复帮砍");
        }
        int rows = recordMapper.update(null, new LambdaUpdateWrapper<BargainRecord>()
                .eq(BargainRecord::getId, record.getId())
                .eq(BargainRecord::getVersion, record.getVersion())
                .eq(BargainRecord::getStatus, 0)
                .set(BargainRecord::getCurrentPriceFen, newPrice)
                .setSql("help_count = help_count + 1, version = version + 1"));
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "砍价状态已变更，请刷新后重试");
        }
        try {
            helpMapper.insert(help);
        } catch (DuplicateKeyException e) {
            // uk_record_helper 兜底：整体回滚本次砍价 CAS
            throw new BizException(ErrorCode.CONFLICT, "您已帮砍过，不能重复帮砍");
        }
    }
}
