package com.shop.e2e.support;

import java.util.function.Supplier;

/**
 * 最终一致性轮询断言工具：MQ 扇出、支付回调、状态机流转都是异步的，
 * E2E 用轮询替代固定 sleep。
 */
public final class Poller {

    private final long timeoutMillis;
    private final long intervalMillis;

    public Poller(long timeoutMillis, long intervalMillis) {
        this.timeoutMillis = timeoutMillis;
        this.intervalMillis = intervalMillis;
    }

    /** 默认 10s 超时、500ms 间隔。 */
    public static Poller def() {
        return new Poller(10_000L, 500L);
    }

    /** 长超时 20s（支付扇出、清算登记等多跳链路）。 */
    public static Poller longTimeout() {
        return new Poller(20_000L, 500L);
    }

    /**
     * 轮询直到断言成立，失败抛 AssertionError。
     *
     * @param description 失败场景描述
     * @param check       返回 true 表示满足；抛异常按未满足处理
     */
    public void await(String description, Supplier<Boolean> check) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AssertionError lastError = null;
        for (;;) {
            try {
                if (Boolean.TRUE.equals(check.get())) {
                    return;
                }
            } catch (AssertionError e) {
                lastError = e;
            } catch (Exception e) {
                lastError = new AssertionError("轮询查询异常: " + e.getMessage(), e);
            }
            if (System.currentTimeMillis() >= deadline) {
                AssertionError err = new AssertionError(
                        "等待超时(" + timeoutMillis + "ms)：" + description
                                + (lastError != null ? "；最后错误：" + lastError.getMessage() : ""));
                if (lastError != null) {
                    err.addSuppressed(lastError);
                }
                throw err;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("轮询被中断", e);
            }
        }
    }

    /** 轮询取数：返回非 null 即成功。 */
    public <T> T awaitValue(String description, Supplier<T> supplier) {
        Object[] holder = new Object[1];
        await(description, () -> {
            T v = supplier.get();
            if (v != null) {
                holder[0] = v;
                return true;
            }
            return false;
        });
        @SuppressWarnings("unchecked")
        T t = (T) holder[0];
        return t;
    }
}
