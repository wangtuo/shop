package com.shop.user.share;

import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.service.AccountService;
import com.shop.user.share.dto.ShareCompleteRequest;
import com.shop.user.share.dto.ShareResultVO;
import com.shop.user.share.entity.UserShareLog;
import com.shop.user.share.mapper.UserShareLogMapper;
import com.shop.user.share.service.impl.ShareServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享完成回调（B6-b）：首次 10 积分（bizNo=SHARE:logId）；日限打满 0 分接口仍成功；
 * 重复 requestNo 幂等返回旧值（前置查重 + UK 冲突两条路径）；不发成长值。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShareServiceImplTest {

    @Mock
    private UserShareLogMapper shareLogMapper;
    @Mock
    private AccountService accountService;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private ShareServiceImpl shareService;

    private ShareCompleteRequest request(String requestNo) {
        ShareCompleteRequest r = new ShareCompleteRequest();
        r.setRequestNo(requestNo);
        r.setTargetType(1);
        r.setTargetId("spu-1");
        return r;
    }

    private UserShareLog stored(long id, String requestNo, long pointsEarned) {
        UserShareLog log = new UserShareLog();
        log.setId(id);
        log.setUserId(1001L);
        log.setRequestNo(requestNo);
        log.setTargetType(1);
        log.setPointsEarned(pointsEarned);
        return log;
    }

    @Test
    void 首次分享_落流水并发放10积分_bizNo为SHARE加流水id() {
        when(shareLogMapper.selectOne(any())).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(8001L);
        when(accountService.grantPoints(any())).thenReturn(10L);

        ShareResultVO vo = shareService.complete(1001L, request("req-1"));

        assertEquals("req-1", vo.getRequestNo());
        assertEquals(10L, vo.getPointsEarned());

        ArgumentCaptor<UserShareLog> insertCaptor = ArgumentCaptor.forClass(UserShareLog.class);
        verify(shareLogMapper).insert(insertCaptor.capture());
        assertEquals(8001L, insertCaptor.getValue().getId());
        assertEquals(1001L, insertCaptor.getValue().getUserId());
        assertEquals("req-1", insertCaptor.getValue().getRequestNo());

        ArgumentCaptor<GrantPointsCommand> cmdCaptor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(accountService).grantPoints(cmdCaptor.capture());
        assertEquals("SHARE:8001", cmdCaptor.getValue().getBizNo());
        assertEquals(10L, cmdCaptor.getValue().getPoints());
        assertEquals(PointsScene.SHARE, cmdCaptor.getValue().getScene());

        ArgumentCaptor<UserShareLog> updateCaptor = ArgumentCaptor.forClass(UserShareLog.class);
        verify(shareLogMapper).updateById(updateCaptor.capture());
        assertEquals(10L, updateCaptor.getValue().getPointsEarned());
    }

    @Test
    void 同日第三单日限打满_grantPoints返回0_回写0且接口成功() {
        when(shareLogMapper.selectOne(any())).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(8003L);
        when(accountService.grantPoints(any())).thenReturn(0L);

        ShareResultVO vo = shareService.complete(1001L, request("req-3"));

        assertEquals(0L, vo.getPointsEarned());
        ArgumentCaptor<UserShareLog> updateCaptor = ArgumentCaptor.forClass(UserShareLog.class);
        verify(shareLogMapper).updateById(updateCaptor.capture());
        assertEquals(0L, updateCaptor.getValue().getPointsEarned());
    }

    @Test
    void 重复requestNo_前置查回旧记录_幂等返回旧积分不重复入账() {
        when(shareLogMapper.selectOne(any())).thenReturn(stored(8001L, "req-dup", 10L));

        ShareResultVO vo = shareService.complete(1001L, request("req-dup"));

        assertEquals(10L, vo.getPointsEarned());
        verify(shareLogMapper, never()).insert(any());
        verify(accountService, never()).grantPoints(any());
        verify(shareLogMapper, never()).updateById(any());
    }

    @Test
    void 并发同requestNo_UK冲突后查回旧记录_幂等返回() {
        when(shareLogMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(stored(8002L, "req-race", 0L));
        when(idGenerator.nextId()).thenReturn(9002L);
        when(shareLogMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_request_no"));

        ShareResultVO vo = shareService.complete(1001L, request("req-race"));

        assertEquals(0L, vo.getPointsEarned());
        verify(accountService, never()).grantPoints(any());
        verify(shareLogMapper, never()).updateById(any());
    }

    @Test
    void 未登录_userId为空_拒绝() {
        BizException ex = assertThrows(BizException.class,
                () -> shareService.complete(null, request("req-x")));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verify(shareLogMapper, never()).insert(any());
    }
}
