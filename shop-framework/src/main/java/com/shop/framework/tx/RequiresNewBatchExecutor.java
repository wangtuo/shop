package com.shop.framework.tx;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 逐元素 REQUIRES_NEW 批处理模板（规约 4，供 P2-4 对账等批处理复用）。
 *
 * <p>每个元素在<b>独立的新事务</b>中处理：第 N 条抛错仅回滚第 N 条，前 N-1 条照常提交，
 * 不会把外层标记为 rollback-only；失败项以 {@link Failure} 形式收集，
 * 由调用方决定继续重试/落差异表/告警（推荐交由重试 Job 接管，不在批内循环重试）。</p>
 *
 * <p>本组件本身是独立 Bean，每个元素的事务由内部 {@link TransactionTemplate} 发起，
 * 天然规避 this 自调用问题；调用方<b>不要</b>把本方法包在自己的事务里
 * （否则外层存在事务时，元素异常仍可能污染外层语义——REQUIRES_NEW 虽挂起外层事务，
 * 但批处理入口保持无事务是本规约的推荐姿势）。</p>
 */
@Component
public class RequiresNewBatchExecutor {

    private final PlatformTransactionManager transactionManager;

    public RequiresNewBatchExecutor(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public <T, R> BatchResult<R> execute(Collection<T> items, ElementAction<T, R> action) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<R> success = new ArrayList<>();
        List<Failure<T>> failed = new ArrayList<>();
        int index = 0;
        for (T item : items) {
            int currentIndex = index++;
            try {
                R result = txTemplate.execute(status -> {
                    try {
                        return action.process(item);
                    } catch (RuntimeException | Error ex) {
                        throw ex;
                    } catch (Exception ex) {
                        // TransactionCallback 不允许 checked exception，包装后仍触发本元素事务回滚
                        throw new ElementExecutionException(ex);
                    }
                });
                success.add(result);
            } catch (Exception e) {
                // 单条事务已回滚；捕获后继续处理后续元素，外层无 rollback-only 污染
                Exception cause = e;
                if (e instanceof ElementExecutionException wrapper
                        && wrapper.getCause() instanceof Exception original) {
                    cause = original;
                }
                failed.add(new Failure<>(currentIndex, item, cause));
            }
        }
        return new BatchResult<>(success, failed);
    }

    /** checked 异常在 REQUIRES_NEW 回调内的运行时包装，回滚后在批处理入口解包还原。 */
    static class ElementExecutionException extends RuntimeException {
        ElementExecutionException(Exception cause) {
            super(cause);
        }
    }

    /** 单元素处理动作，运行在独立 REQUIRES_NEW 事务内；只允许 DB + outbox（规约 3）。 */
    @FunctionalInterface
    public interface ElementAction<T, R> {
        R process(T item) throws Exception;
    }

    /** 失败元素及其异常快照。 */
    public record Failure<T>(int index, T item, Exception cause) {
    }

    /**
     * 批处理结果。{@code failed} 非空即存在未处理元素，调用方不得把整批当成功。
     */
    public static class BatchResult<R> {
        private final List<R> success;
        private final List<Failure<?>> failed;

        public BatchResult(List<R> success, List<? extends Failure<?>> failed) {
            this.success = List.copyOf(success);
            this.failed = List.copyOf(failed);
        }

        public List<R> getSuccess() {
            return success;
        }

        @SuppressWarnings("unchecked")
        public <T> List<Failure<T>> getFailed() {
            return (List<Failure<T>>) (List<?>) failed;
        }

        public boolean hasFailures() {
            return !failed.isEmpty();
        }
    }
}
