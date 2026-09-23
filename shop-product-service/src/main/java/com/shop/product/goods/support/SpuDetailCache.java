package com.shop.product.goods.support;

import com.shop.common.util.JsonUtils;
import com.shop.product.goods.dto.SpuDetailVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * SPU 详情缓存：key 带版本（product:spu:detail:{spuId}:v{version}），
 * 商品写操作/状态流转后删缓存（cache-aside）。
 *
 * <p>无 Redis（RedissonClient 缺失或不可用）时直接回源，不抛异常（design 3.x 浏览降级）。</p>
 */
@Component
@RequiredArgsConstructor
public class SpuDetailCache {

    private static final Logger log = LoggerFactory.getLogger(SpuDetailCache.class);
    private static final String KEY_PREFIX = "product:spu:detail:";

    private final ObjectProvider<RedissonClient> redissonProvider;

    public String key(Long spuId, long version) {
        return KEY_PREFIX + spuId + ":v" + version;
    }

    /** 读缓存；无 Redis / 反序列化失败返回 null 由调用方回源。 */
    public SpuDetailVO get(Long spuId, long version) {
        RedissonClient client = clientOrNull();
        if (client == null) {
            return null;
        }
        try {
            RBucket<String> bucket = client.getBucket(key(spuId, version));
            String json = bucket.get();
            return json == null ? null : JsonUtils.fromJson(json, SpuDetailVO.class);
        } catch (Exception e) {
            log.warn("读取 SPU 详情缓存失败 spuId={}", spuId, e);
            return null;
        }
    }

    /** 写缓存；失败仅降级不影响主流程。 */
    public void put(Long spuId, long version, SpuDetailVO detail) {
        RedissonClient client = clientOrNull();
        if (client == null) {
            return;
        }
        try {
            client.getBucket(key(spuId, version)).set(JsonUtils.toJson(detail));
        } catch (Exception e) {
            log.warn("写入 SPU 详情缓存失败 spuId={}", spuId, e);
        }
    }

    /**
     * 写后删缓存：按版本通配删除该 SPU 的全部历史版本 key。
     */
    public void evict(Long spuId) {
        RedissonClient client = clientOrNull();
        if (client == null) {
            return;
        }
        try {
            client.getKeys().deleteByPattern(KEY_PREFIX + spuId + ":v*");
        } catch (Exception e) {
            log.warn("删除 SPU 详情缓存失败 spuId={}", spuId, e);
        }
    }

    private RedissonClient clientOrNull() {
        try {
            return redissonProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }
}
