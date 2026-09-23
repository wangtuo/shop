package com.shop.framework.lock;

/**
 * 分布式锁回调（有返回值）。
 */
@FunctionalInterface
public interface LockCallback<T> {

    T execute();
}
