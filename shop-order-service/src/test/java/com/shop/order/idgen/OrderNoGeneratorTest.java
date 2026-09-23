package com.shop.order.idgen;

import com.shop.framework.id.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单号生成器单测（不连中间件，mock Redisson）：
 * YYMMDD + 2 位业务类型 + 用户 ID 后 4 位 + 6 位日内序列，共 18 位。
 */
@ExtendWith(MockitoExtension.class)
class OrderNoGeneratorTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RAtomicLong atomicLong;
    @Mock
    private IdGenerator idGenerator;

    @InjectMocks
    private OrderNoGenerator generator;

    private final LocalDate date = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
    }

    @Test
    void next_shouldBe18Chars_withDateBizUserAndSequence() {
        when(atomicLong.incrementAndGet()).thenReturn(7L);

        String no = generator.next(date, 1, 123L);

        // 260315 + 01 + 0123 + 000007
        assertThat(no).isEqualTo("260315010123000007");
        assertThat(no).hasSize(18);
        verify(redissonClient).getAtomicLong("shop:order:seq:260315:01");
    }

    @Test
    void next_eachBizTypeUsesOwnSequenceKey() {
        when(atomicLong.incrementAndGet()).thenReturn(1L);

        generator.next(date, 2, 1L);
        generator.next(date, 3, 1L);
        generator.next(date, 4, 1L);
        generator.next(date, 5, 1L);

        verify(redissonClient).getAtomicLong("shop:order:seq:260315:02");
        verify(redissonClient).getAtomicLong("shop:order:seq:260315:03");
        verify(redissonClient).getAtomicLong("shop:order:seq:260315:04");
        verify(redissonClient).getAtomicLong("shop:order:seq:260315:05");
    }

    @Test
    void next_firstIncrOfDay_sets48hTtl() {
        when(atomicLong.incrementAndGet()).thenReturn(1L);

        generator.next(date, 1, 1L);

        verify(atomicLong).expire((Duration) eq(Duration.ofHours(48)));
    }

    @Test
    void next_subsequentIncr_doesNotRefreshTtl() {
        when(atomicLong.incrementAndGet()).thenReturn(42L);

        generator.next(date, 1, 1L);

        verify(atomicLong, never()).expire((Duration) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void next_sequenceOverflow_fallsBackToSnowflake() {
        when(atomicLong.incrementAndGet()).thenReturn(1_000_000L);
        when(idGenerator.nextId()).thenReturn(1234567890123456789L);

        String no = generator.next(date, 1, 1L);

        assertThat(no).isEqualTo("1234567890123456789");
        verify(idGenerator).nextId();
    }

    @Test
    void next_sequenceExactly999999_stillUsesSequence() {
        when(atomicLong.incrementAndGet()).thenReturn(999_999L);

        String no = generator.next(date, 1, 1L);

        assertThat(no).hasSize(18).endsWith("999999");
        verify(idGenerator, never()).nextId();
    }

    @Test
    void userSuffix_shortIdsLeftPadWithZero() {
        assertThat(OrderNoGenerator.userSuffix(1L)).isEqualTo("0001");
        assertThat(OrderNoGenerator.userSuffix(12L)).isEqualTo("0012");
        assertThat(OrderNoGenerator.userSuffix(123L)).isEqualTo("0123");
        assertThat(OrderNoGenerator.userSuffix(1234L)).isEqualTo("1234");
    }

    @Test
    void userSuffix_longIdsKeepLast4Digits() {
        assertThat(OrderNoGenerator.userSuffix(123456789L)).isEqualTo("6789");
        assertThat(OrderNoGenerator.userSuffix(10000L)).isEqualTo("0000");
    }

    @Test
    void userSuffix_null_throws() {
        assertThatThrownBy(() -> OrderNoGenerator.userSuffix(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void next_unknownBizType_throws() {
        assertThatThrownBy(() -> generator.next(date, 9, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
