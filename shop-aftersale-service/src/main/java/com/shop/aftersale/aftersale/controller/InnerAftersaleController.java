package com.shop.aftersale.aftersale.controller;

import com.shop.api.aftersale.client.AftersaleClient;
import com.shop.api.aftersale.dto.MerchantDisputeDTO;
import com.shop.aftersale.aftersale.service.MerchantDisputeQueryService;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 售后域内部接口（FUNDS C8），路径与 {@link AftersaleClient} 的
 * {@code @FeignClient(path="/inner/aftersale")} 逐字一致：GET /inner/aftersale/disputes/exists。
 *
 * <p>{@code @Anonymous} 免网关登录鉴权；/inner/** 另由框架 {@code InternalTokenInterceptor}
 * 校验 X-Internal-Token（仅服务间 Feign 经 FeignRequestInterceptor 注入，网关外部不可达）。
 */
@Anonymous
@RestController
@RequestMapping("/inner/aftersale")
@RequiredArgsConstructor
public class InnerAftersaleController {

    private final MerchantDisputeQueryService merchantDisputeQueryService;

    @GetMapping("/disputes/exists")
    public Result<MerchantDisputeDTO> existsOpenDispute(@RequestParam("merchantId") Long merchantId,
                                                        @RequestParam("since") LocalDateTime since) {
        return Result.success(merchantDisputeQueryService.existsOpenDispute(merchantId, since));
    }
}
