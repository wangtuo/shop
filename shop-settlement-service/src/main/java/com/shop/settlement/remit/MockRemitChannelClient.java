package com.shop.settlement.remit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock 代发渠道：本地/CI 默认启用（{@code shop.settle.remit.mock-enabled} 缺省 true）。
 *
 * <p>默认行为模拟「快速到账的真实渠道」：{@link #remit} 只受理并生成渠道流水号
 * （受理时不代表打款成功，资金方不得记账），随后 {@link #query} 返回成功——资金链路与真实渠道
 * 一致，严格杜绝 mock 时代「提交即成功」的旧语义。</p>
 *
 * <p>查询补偿联调开关（均可选，默认关闭）：</p>
 * <ul>
 *   <li>{@code shop.settle.remit.mock.pending=true}：query 恒返回处理中，模拟渠道长时间未终态；</li>
 *   <li>{@code.shop.settle.remit.mock.failing=true}：remit 恒受理拒绝，模拟渠道故障；</li>
 *   <li>{@code shop.settle.remit.mock.accept-delay-ms}：受理延迟（毫秒），用于断言调用方不持 DB 事务。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shop.settle.remit.mock-enabled", matchIfMissing = true)
public class MockRemitChannelClient implements RemitChannelClient {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final AtomicLong seq = new AtomicLong(0);
    /** bizNo -> 渠道流水号（模拟渠道侧幂等登记） */
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    /** 测试专用：bizNo -> 强制 query 终态（RemitStatuses） */
    private final Map<String, Integer> overrides = new ConcurrentHashMap<>();

    @Value("${shop.settle.remit.mock.pending:false}")
    private boolean pendingAlways;
    @Value("${shop.settle.remit.mock.failing:false}")
    private boolean failingAlways;
    @Value("${shop.settle.remit.mock.accept-delay-ms:0}")
    private long acceptDelayMs;

    @Override
    public boolean supports(Integer channel) {
        return channel != null && (channel == 1 || channel == 2);
    }

    @Override
    public RemitResult remit(RemitRequest request) {
        if (failingAlways) {
            return RemitResult.rejected("mock 渠道故障（shop.settle.remit.mock.failing=true）");
        }
        sleepAcceptDelay();
        // 渠道幂等键 = bizNo：重复提交返回同一渠道流水号，绝不重复代发
        String channelRemitNo = accepted.computeIfAbsent(request.getBizNo(),
                k -> "MOCK" + LocalDateTime.now().format(TS) + String.format("%06d", seq.incrementAndGet()));
        log.info("mock 代发受理 bizNo={} merchantId={} amount={} channelRemitNo={}",
                request.getBizNo(), request.getMerchantId(), request.getAmountFen(), channelRemitNo);
        return RemitResult.accepted(channelRemitNo);
    }

    @Override
    public RemitQueryResult query(RemitQueryRequest request) {
        String known = accepted.get(request.getBizNo());
        String channelRemitNo = request.getChannelRemitNo() != null && !request.getChannelRemitNo().isBlank()
                ? request.getChannelRemitNo() : known;
        if (channelRemitNo == null) {
            // 渠道侧无记录：业务库本不该进入查询阶段，防御性按失败返回（不伪成功）
            return RemitQueryResult.fail("", "mock 渠道无此代发记录: " + request.getBizNo());
        }
        Integer forced = overrides.get(request.getBizNo());
        if (forced != null) {
            if (forced == RemitStatuses.SUCCESS) {
                return RemitQueryResult.success(channelRemitNo);
            }
            if (forced == RemitStatuses.FAIL) {
                return RemitQueryResult.fail(channelRemitNo, "mock 单笔失败注入");
            }
            return RemitQueryResult.processing();
        }
        if (pendingAlways) {
            return RemitQueryResult.processing();
        }
        return RemitQueryResult.success(channelRemitNo);
    }

    private void sleepAcceptDelay() {
        if (acceptDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(acceptDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("mock 代发受理延迟被中断", e);
        }
    }

    // ---------------- 测试辅助（单测精确控制单笔终态） ----------------

    /** 令指定 bizNo 的 query 恒返回处理中。 */
    public void forceProcessing(String bizNo) {
        overrides.put(bizNo, RemitStatuses.PROCESSING);
    }

    /** 令指定 bizNo 的 query 返回失败。 */
    public void forceFail(String bizNo) {
        overrides.put(bizNo, RemitStatuses.FAIL);
    }

    /** 令指定 bizNo 的 query 返回成功（取消单笔注入）。 */
    public void forceSuccess(String bizNo) {
        overrides.put(bizNo, RemitStatuses.SUCCESS);
    }

    /** 清除内存受理记录与单笔覆盖（单测间隔离）。 */
    public void reset() {
        accepted.clear();
        overrides.clear();
    }
}
