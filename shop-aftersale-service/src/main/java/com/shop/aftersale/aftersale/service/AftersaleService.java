package com.shop.aftersale.aftersale.service;

import com.shop.aftersale.aftersale.dto.AftersaleApplyRequest;
import com.shop.aftersale.aftersale.dto.AftersaleDetailVO;
import com.shop.aftersale.aftersale.dto.AftersalePageQuery;
import com.shop.aftersale.aftersale.dto.ArbitrateRequest;
import com.shop.aftersale.aftersale.dto.EvidenceRequest;
import com.shop.aftersale.aftersale.dto.LogisticsRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialVO;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.framework.web.LoginUser;
import com.shop.common.result.PageResult;

/**
 * 售后主流程服务。
 */
public interface AftersaleService {

    String apply(AftersaleApplyRequest request, Long userId);

    void cancel(String aftersaleNo, Long userId);

    void resubmit(String aftersaleNo, AftersaleApplyRequest request, Long userId);

    void audit(String aftersaleNo, Long merchantId, boolean agree, String rejectReason);

    void fillReturnLogistics(String aftersaleNo, Long userId, LogisticsRequest request);

    void merchantReceive(String aftersaleNo, Long merchantId, boolean accept, String rejectReason);

    void shipExchange(String aftersaleNo, Long merchantId, LogisticsRequest request);

    void confirmExchange(String aftersaleNo, Long userId);

    void applyIntervene(String aftersaleNo, Long userId);

    void submitEvidence(String aftersaleNo, int side, Long operatorId, EvidenceRequest request);

    void arbitrate(String aftersaleNo, ArbitrateRequest request);

    PriceProtectTrialVO trialPriceProtect(PriceProtectTrialRequest request, Long userId);

    /**
     * 售后单详情（H-2）：买家本人、订单归属商户或平台运营可查，其余身份 403。
     */
    AftersaleDetailVO detail(String aftersaleNo, LoginUser viewer);

    PageResult<AftersaleOrder> pageUser(AftersalePageQuery query, Long userId);

    PageResult<AftersaleOrder> pageMerchant(AftersalePageQuery query, Long merchantId);

    // ---------- 超时双保险：系统自动流转 ----------

    void autoApprove(String aftersaleNo);

    void autoConfirmReceive(String aftersaleNo);

    void autoConvertExchangeToRefund(String aftersaleNo);
}
