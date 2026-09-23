package com.shop.pay.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.shop.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock 渠道适配器：模拟微信/支付宝/银联/云闪付/花呗/白条下单、查询、退款与对账单下载。
 *
 * <p>B8 双轨：默认生效（{@code shop.pay.mock-channels-enabled} 缺省 true），{@link Order}=100
 * 排在真实渠道骨架（Order=0）之后——真实渠道 bean 存在且 {@code supports} 命中时优先 real，
 * 否则 mock 兜底；prod 关闭 mock（{@code shop.pay.mock-channels-enabled=false}）后本 bean 不装配，
 * 由 {@link ChannelSecretProvider#validate()} 保证缺真实凭证时 fail-fast。</p>
 *
 * <p>真实环境替换为各渠道 SDK 实现即可，支付域业务代码不变。对账单支持从 classpath
 * {@code recon/mock-bill-{channelCode}-{yyyy-MM-dd}.json} 加载 mock 文件，缺省为空。</p>
 */
@Component
@Order(100)
@ConditionalOnProperty(value = "shop.pay.mock-channels-enabled", havingValue = "true", matchIfMissing = true)
public class MockPayChannelClient implements PayChannelClient {

    private static final Logger log = LoggerFactory.getLogger(MockPayChannelClient.class);

    private final AtomicLong seq = new AtomicLong(0);

    /** 测试 / 灰度开关：主动查询时是否模拟"用户已付款/退款成功"，生产 mock 默认成功；置 false 时查询返回受理中 */
    @Value("${shop.pay.mock.query-success:true}")
    private boolean querySuccess;

    @Override
    public boolean supports(String channelCode) {
        return !ChannelLimits.isBalance(channelCode);
    }

    @Override
    public ChannelPayResult createOrder(ChannelPayRequest request) {
        String channelOrderNo = request.getChannelCode() + "_O_" + request.getPayNo() + "_" + seq.incrementAndGet();
        String payUrl = "mock://cashier/" + request.getChannelCode() + "?order=" + channelOrderNo
                + "&amount=" + request.getAmountFen();
        log.info("[MOCK渠道] 下单受理 channel={} payNo={} amount={} channelOrderNo={}",
                request.getChannelCode(), request.getPayNo(), request.getAmountFen(), channelOrderNo);
        return ChannelPayResult.builder()
                .channelCode(request.getChannelCode())
                .channelOrderNo(channelOrderNo)
                .payUrl(payUrl)
                .accepted(true)
                .build();
    }

    @Override
    public ChannelQueryResult query(String channelCode, String channelOrderNo) {
        if (!querySuccess) {
            return ChannelQueryResult.of(ChannelQueryResult.State.PAYING);
        }
        return ChannelQueryResult.builder()
                .state(ChannelQueryResult.State.SUCCESS)
                .channelTxnNo(channelCode + "_T_" + channelOrderNo)
                .build();
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        log.info("[MOCK渠道] 原路退款 channel={} refundNo={} amount={}",
                request.getChannelCode(), request.getRefundNo(), request.getAmountFen());
        return ChannelRefundResult.ok(request.getChannelCode(),
                request.getChannelCode() + "_R_" + request.getRefundNo());
    }

    @Override
    public ChannelRefundQueryResult queryRefund(ChannelRefundQueryRequest request) {
        // shop.pay.mock.query-success=false：模拟渠道受理中，退款单保持 PROCESSING 等待下轮补偿/回调
        if (!querySuccess) {
            return ChannelRefundQueryResult.processing(request.getChannelCode());
        }
        String channelRefundNo = request.getChannelRefundNo() != null
                ? request.getChannelRefundNo()
                : request.getChannelCode() + "_R_" + request.getRequestNo();
        return ChannelRefundQueryResult.ok(request.getChannelCode(), channelRefundNo);
    }

    @Override
    public List<ChannelBillRecord> downloadBill(String channelCode, LocalDate billDate) {
        String path = "recon/mock-bill-" + channelCode + "-" + billDate + ".json";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return new ArrayList<>();
            }
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return JsonUtils.fromJson(json, new TypeReference<List<ChannelBillRecord>>() {
            });
        } catch (Exception e) {
            log.warn("[MOCK渠道] 对账单加载失败 path={} err={}", path, e.getMessage());
            return new ArrayList<>();
        }
    }
}
