package com.shop.framework.tx;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 编程式 REQUIRED 事务模板（规约 1/3）。
 *
 * <p>语义等价于 {@code @Transactional(propagation = REQUIRED, rollbackFor = Exception.class)}：
 * 当前线程已有事务则加入，没有则新建；回调抛出任何 {@link Exception}（含 checked）都触发回滚，
 * checked 异常被包装为 {@link TransactionalExecutionException}（RuntimeException）原样抛出，cause 保留。</p>
 *
 * <p><b>使用边界（规约 3）</b>：回调内只允许 DB 操作与 {@code OutboxPublisher} 事件登记，
 * 禁止 Feign/HTTP/MQ 直发/Redis 长操作；远程调用放在回调外，或采用"短事务落 PROCESSING 提交 →
 * 事务外远程调用 → 独立短事务 CAS 终态"三段式。</p>
 */
@Component
public class TransactionalTemplate {

    private final TransactionTemplate transactionTemplate;

    public TransactionalTemplate(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** 在 REQUIRED 事务内执行并返回结果。 */
    public <T> T execute(Callback<T> callback) {
        return transactionTemplate.execute(status -> {
            try {
                return callback.doInTransaction();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                // 等价 rollbackFor = Exception.class：checked exception 也强制回滚
                status.setRollbackOnly();
                throw new TransactionalExecutionException(e);
            }
        });
    }

    /** 在 REQUIRED 事务内执行无返回值逻辑。 */
    public void executeWithoutResult(Action action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    /** 有返回值回调，允许声明 checked exception（模板负责回滚+包装）。 */
    @FunctionalInterface
    public interface Callback<T> {
        T doInTransaction() throws Exception;
    }

    /** 无返回值回调。 */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** checked 异常经模板回滚后的运行时包装，{@link #getCause()} 为原始业务异常。 */
    public static class TransactionalExecutionException extends RuntimeException {
        public TransactionalExecutionException(Exception cause) {
            super(cause);
        }
    }
}
