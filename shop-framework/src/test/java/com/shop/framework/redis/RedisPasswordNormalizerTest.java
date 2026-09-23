package com.shop.framework.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空白密码归一化三态 + sentinel 半卡：
 * 空字符串/空白→null（修复 Redisson 3.27.2 AUTH "" 启动失败）；null 与真实密码原样保留。
 */
class RedisPasswordNormalizerTest {

    private final RedisPasswordNormalizer normalizer = new RedisPasswordNormalizer();

    @Test
    void 空白密码_归一为null() {
        RedisProperties props = new RedisProperties();
        props.setPassword("");

        Object result = normalizer.postProcessAfterInitialization(props,
                "org.springframework.boot.autoconfigure.data.redis.RedisProperties");

        assertThat(result).isSameAs(props);
        assertThat(props.getPassword()).isNull();
    }

    @Test
    void 纯空白密码_归一为null() {
        RedisProperties props = new RedisProperties();
        props.setPassword("  \t ");

        normalizer.postProcessAfterInitialization(props, "redisProperties");

        assertThat(props.getPassword()).isNull();
    }

    @Test
    void null密码_保持null不报错() {
        RedisProperties props = new RedisProperties();
        assertThat(props.getPassword()).isNull();

        normalizer.postProcessAfterInitialization(props, "redisProperties");

        assertThat(props.getPassword()).isNull();
    }

    @Test
    void 真实密码_原样透传() {
        RedisProperties props = new RedisProperties();
        props.setPassword("s3cr3t-from-kms");

        normalizer.postProcessAfterInitialization(props, "redisProperties");

        assertThat(props.getPassword()).isEqualTo("s3cr3t-from-kms");
    }

    @Test
    void 非RedisPropertiesBean_原样返回() {
        Object other = new Object();
        assertThat(normalizer.postProcessAfterInitialization(other, "anyBean")).isSameAs(other);
    }

    @Test
    void sentinel空白密码_同步归一() {
        RedisProperties props = new RedisProperties();
        props.setPassword("real-password");
        RedisProperties.Sentinel sentinel = new RedisProperties.Sentinel();
        sentinel.setPassword("");
        props.setSentinel(sentinel);

        normalizer.postProcessAfterInitialization(props, "redisProperties");

        assertThat(props.getPassword()).isEqualTo("real-password");
        assertThat(props.getSentinel().getPassword()).isNull();
    }
}
