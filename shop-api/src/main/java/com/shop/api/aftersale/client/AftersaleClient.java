package com.shop.api.aftersale.client;

import com.shop.api.aftersale.dto.MerchantDisputeDTO;
import com.shop.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;

/**
 * 售后域跨服务 Feign 契约（FUNDS C8）。
 *
 * <p>服务端必须在 {@code shop-aftersale-service} 的 {@code /inner/aftersale} 同路径实现。
 */
@FeignClient(name = "shop-aftersale-service", path = "/inner/aftersale")
public interface AftersaleClient {

    /**
     * 查询商户在指定时间之后是否存在未终结售后/介入单（保证金提现/扣款判据）。
     *
     * @param merchantId 商户 ID
     * @param since      起始时间（含）
     * @return 存在性与计数快照
     */
    @GetMapping("/disputes/exists")
    Result<MerchantDisputeDTO> existsOpenDispute(@RequestParam("merchantId") Long merchantId,
                                                 @RequestParam("since") LocalDateTime since);
}
