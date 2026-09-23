package com.shop.aftersale.aftersale.service;

import com.shop.api.aftersale.dto.MerchantDisputeDTO;
import com.shop.aftersale.aftersale.mapper.AftersaleDisputeMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 商户未终结售后/介入单查询（FUNDS C8，保证金提现/扣款判据）。
 *
 * <p>openCount = since 之后未终结售后单（status ∉ {50,55,90}）+ 介入中纠纷单（status ≠ 30）；
 * recentFinishedCount = since 之后近期已终结售后单；exists 以 openCount 是否为 0 判定。
 */
@Service
@RequiredArgsConstructor
public class MerchantDisputeQueryService {

    private final AftersaleOrderMapper orderMapper;
    private final AftersaleDisputeMapper disputeMapper;

    public MerchantDisputeDTO existsOpenDispute(Long merchantId, LocalDateTime since) {
        long openOrders = orderMapper.countOpenByMerchantSince(merchantId, since);
        long openDisputes = disputeMapper.countRecentIntervene(merchantId, since);
        long recentFinished = orderMapper.countFinishedByMerchantSince(merchantId, since);
        long openCount = openOrders + openDisputes;
        return MerchantDisputeDTO.builder()
                .exists(openCount > 0)
                .openCount(openCount)
                .recentFinishedCount(recentFinished)
                .build();
    }
}
