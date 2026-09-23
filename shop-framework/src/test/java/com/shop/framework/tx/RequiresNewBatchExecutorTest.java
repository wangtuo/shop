package com.shop.framework.tx;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R-B5 规约 4：逐元素 REQUIRES_NEW——第 2 条失败时第 1/3 条照常提交，
 * 失败项进失败列表，传播级别恒为 REQUIRES_NEW，外层无 rollback-only 污染。
 */
class RequiresNewBatchExecutorTest {

    @Test
    void eachElementRunsInRequiresNewAndFailureIsIsolated() {
        AtomicCount commits = new AtomicCount();
        AtomicCount rollbacks = new AtomicCount();
        TransactionDefinition[] seen = new TransactionDefinition[1];

        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(invocation -> {
            seen[0] = invocation.getArgument(0);
            // 每次返回“新事务”状态，模拟 REQUIRES_NEW 物理新事务
            return new SimpleTransactionStatus(true);
        });
        doAnswer(invocation -> {
            commits.value++;
            return null;
        }).when(tm).commit(any());
        doAnswer(invocation -> {
            rollbacks.value++;
            return null;
        }).when(tm).rollback(any());

        RequiresNewBatchExecutor executor = new RequiresNewBatchExecutor(tm);

        RequiresNewBatchExecutor.BatchResult<String> result = executor.execute(
                List.of(1, 2, 3),
                item -> {
                    if (item == 2) {
                        throw new IllegalStateException("第2条处理失败");
                    }
                    return "ok-" + item;
                });

        assertThat(seen[0].getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(result.getSuccess()).containsExactly("ok-1", "ok-3");
        assertThat(result.hasFailures()).isTrue();
        List<RequiresNewBatchExecutor.Failure<Integer>> failed = result.getFailed();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).index()).isEqualTo(1);
        assertThat(failed.get(0).item()).isEqualTo(2);
        assertThat(failed.get(0).cause()).isInstanceOf(IllegalStateException.class);
        // 第 1、3 条各自提交，第 2 条独立回滚
        assertThat(commits.value).isEqualTo(2);
        assertThat(rollbacks.value).isEqualTo(1);
    }

    private static final class AtomicCount {
        int value;
    }
}
