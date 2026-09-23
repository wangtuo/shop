package com.shop.framework.lock;

/**
 * 分布式锁回调（无返回值）。
 */
@FunctionalInterface
public interface LockAction {

    void execute();
}
