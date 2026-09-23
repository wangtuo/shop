package com.shop.user.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.service.AccountService;
import com.shop.user.member.PointsCalc;
import com.shop.user.share.dto.ShareCompleteRequest;
import com.shop.user.share.dto.ShareResultVO;
import com.shop.user.share.entity.UserShareLog;
import com.shop.user.share.mapper.UserShareLogMapper;
import com.shop.user.share.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 分享完成回调实现（B6-b）。
 *
 * <p>幂等三层：requestNo 查重 → uk_request_no 并发兜底（catch 后查回旧记录）→ 积分 bizNo=SHARE:{logId} UK。
 * 日限 20 由 grantPoints + t_user_points_daily clamp，打满回写 points_earned=0，接口仍成功；
 * 分享不发成长值（design 2.1.3 无此规则）。
 */
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareServiceImpl.class);

    private final UserShareLogMapper shareLogMapper;
    private final AccountService accountService;
    private final IdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareResultVO complete(Long userId, ShareCompleteRequest request) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (request == null || request.getRequestNo() == null || request.getRequestNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "幂等号不能为空");
        }
        UserShareLog existing = shareLogMapper.selectOne(new LambdaQueryWrapper<UserShareLog>()
                .eq(UserShareLog::getRequestNo, request.getRequestNo()));
        if (existing != null) {
            return toVO(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        UserShareLog shareLog = new UserShareLog();
        shareLog.setId(idGenerator.nextId());
        shareLog.setUserId(userId);
        shareLog.setRequestNo(request.getRequestNo());
        shareLog.setTargetType(request.getTargetType());
        shareLog.setTargetId(request.getTargetId());
        shareLog.setPointsEarned(0L);
        shareLog.setShareTime(now);
        shareLog.setCreateTime(now);
        try {
            shareLogMapper.insert(shareLog);
        } catch (DuplicateKeyException e) {
            // 并发同 requestNo：负方查回旧记录，幂等返回旧积分
            UserShareLog old = shareLogMapper.selectOne(new LambdaQueryWrapper<UserShareLog>()
                    .eq(UserShareLog::getRequestNo, request.getRequestNo()));
            if (old == null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "分享记录冲突且查无原记录");
            }
            log.info("重复分享回调幂等返回 requestNo={} pointsEarned={}",
                    request.getRequestNo(), old.getPointsEarned());
            return toVO(old);
        }

        // 日限 20 clamp 发生在 grantPoints 内部：打满返回 0，不抛异常
        long points = accountService.grantPoints(GrantPointsCommand.builder()
                .userId(userId)
                .bizNo("SHARE:" + shareLog.getId())
                .points(PointsCalc.SHARE_POINTS)
                .scene(PointsScene.SHARE)
                .build());
        shareLog.setPointsEarned(points);
        shareLogMapper.updateById(shareLog);
        return toVO(shareLog);
    }

    private ShareResultVO toVO(UserShareLog shareLog) {
        return ShareResultVO.builder()
                .requestNo(shareLog.getRequestNo())
                .pointsEarned(shareLog.getPointsEarned())
                .build();
    }
}
