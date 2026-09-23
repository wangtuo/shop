package com.shop.settlement.withdraw.controller;

import com.shop.common.exception.BizException;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.withdraw.dto.ApplyWithdrawRequest;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.service.WithdrawService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M-3：withdrawNo 只来自服务端预生成（body 伪造值被覆盖），merchantId 只来自登录身份。 */
@ExtendWith(MockitoExtension.class)
class MerchantWithdrawControllerTest {

    @Mock
    private WithdrawService withdrawService;
    @Mock
    private SettleNoGenerator noGenerator;

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private MerchantWithdrawController controller() {
        return new MerchantWithdrawController(withdrawService, noGenerator);
    }

    private ApplyWithdrawRequest request() {
        ApplyWithdrawRequest req = new ApplyWithdrawRequest();
        req.setAmountFen(100_000L);
        req.setChannel(1);
        req.setChannelAccount("6222020200001234");
        req.setAccountName("张三");
        return req;
    }

    @Test
    void 服务端预生成单号覆盖body伪造值() {
        UserContext.set(LoginUser.builder().userId(1L).userType(1).merchantId(777L).build());
        when(noGenerator.nextWithdrawNo()).thenReturn("WD2609160000000007");
        SettWithdraw saved = new SettWithdraw();
        saved.setWithdrawNo("WD2609160000000007");
        ApplyWithdrawRequest req = request();
        req.setWithdrawNo("WDFORGED-CLIENT");
        when(withdrawService.apply(eq(777L), eq(req), anyBoolean())).thenReturn(saved);

        String no = controller().apply(req).getData();

        assertEquals("WD2609160000000007", no);
        assertEquals("WD2609160000000007", req.getWithdrawNo());
        ArgumentCaptor<ApplyWithdrawRequest> cap = ArgumentCaptor.forClass(ApplyWithdrawRequest.class);
        verify(withdrawService).apply(eq(777L), cap.capture(), eq(false));
        assertEquals("WD2609160000000007", cap.getValue().getWithdrawNo());
    }

    @Test
    void 消费者无商户身份申请提现403() {
        UserContext.set(LoginUser.builder().userId(1L).userType(0).build());
        BizException ex = assertThrows(BizException.class, () -> controller().apply(request()));
        assertEquals(10003, ex.getCode());
    }
}
