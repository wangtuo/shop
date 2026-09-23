package com.shop.pay.feature.refund.controller;

import com.shop.api.pay.dto.RefundDTO;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.pay.feature.refund.service.RefundService;
import com.shop.pay.support.WebIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外退款查询/重试接口（C-3 收口）：
 * <ul>
 *   <li>C 端不再有创建退款入口——退款只能由售后流程经内部 Feign（/inner/pay/refund）触发；</li>
 *   <li>查询仅平台运营、退款单关联订单的归属商户或退款买家本人可访问；</li>
 *   <li>失败重试仅平台运营可触发。</li>
 * </ul>
 */
@RestController
@RequestMapping("/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @GetMapping("/{refundNo}")
    public Result<RefundDTO> get(@PathVariable("refundNo") String refundNo) {
        return Result.success(refundService.getByRefundNoForViewer(refundNo, WebIdentity.requireUser()));
    }

    /** 失败退款补偿重试（仅平台运营触发）。 */
    @PostMapping("/{refundNo}/retry")
    @AuditLog(action = "REFUND_RETRY", targetType = "REFUND",
            targetIdSpEL = "#refundNo", captureArgs = true)
    public Result<RefundDTO> retry(@PathVariable("refundNo") String refundNo) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(refundService.retry(refundNo));
    }
}
