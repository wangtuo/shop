package com.shop.framework.mq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 消费端事务规约基类（P3-1 / R-B5）：先<b>事务外</b>预取/校验，再开<b>短事务</b>
 * 只做 DB 写入 + outbox。供 settlement PaymentSucceededListener、aftersale 理赔等
 * 「消费事务内夹 Feign 远程调用」高风险链路改造使用。
 *
 * <p><b>为什么必须两段式：</b>Feign/远程调用放进数据库事务会让行锁/连接池持有时间
 * 被网络 RTT 与下游超时绑架（慢响应 → 连接池耗尽、锁等待雪崩）；而只有 DB 写入需要
 * 事务原子性。因此远程读到的数据在事务外取齐，事务内只做「校验本地前置状态 → 写业务表
 * → 写 outbox/幂等流水」。
 *
 * <p><b>子类实现 checklist（评审逐项过）：</b>
 * <ol>
 *   <li>{@link #prefetchAndValidate(Object)} 在<b>无事务</b>上下文执行：允许 Feign 预取、
 *       参数校验、金额/签名校验；终态错误（{@link MqErrorPolicy} 中的 4xx 语义）直接抛
 *       BizException，框架 ACK 丢弃；可恢复异常返回 FAILURE 由 broker 重试；</li>
 *   <li>预取阶段对「引用聚合暂时查不到」不要抛终态异常（返回 FAILURE 等重试，见
 *       {@link MqErrorPolicy} NOT_FOUND 口径）；</li>
 *   <li>{@link #persistInShortTransaction(Object)} 内<b>禁止</b>任何 Feign/HTTP/RPC/Redis
 *       长操作，只允许本地 DB 读写与 outbox 插入；事务时长目标 p95 &lt; 100ms；</li>
 *   <li>幂等：persist 内首步用 {@link MqConsumeContext#currentEventId()} 取统一 eventId
 *       INSERT IGNORE 消费流水（eventId 已由框架保证非空，禁止再私拼 noid）；</li>
 *   <li>需要再发下游事件时，与业务状态变更同事务写 outbox（禁止事务内直送 MQ）；</li>
 *   <li>prefetch 与 persist 之间存在并发窗口：persist 内必须用 CAS/唯一键/乐观锁做二次
 *       状态校验，不能假设预取结果在事务内仍成立；</li>
 *   <li>消息重复（至少一次投递）下整个流程必须天然幂等。</li>
 * </ol>
 *
 * @param <P> 消息载荷类型
 */
public abstract class AbstractTransactionalListener<P> implements MqListener<P> {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    /**
     * 模板方法（final）：事务外预取校验 → 短事务落库。子类不要重写本方法。
     */
    @Override
    public final void onMessage(P message) {
        P validated = prefetchAndValidate(message);
        if (validated == null) {
            // 显式 ACK 跳过（重复/无需处理场景），由子类自行负责日志
            return;
        }
        transactionTemplate().executeWithoutResult(status -> persistInShortTransaction(validated));
    }

    /**
     * 事务外阶段：远程预取、参数/金额/签名校验。直接返回入参（可补充/裁剪字段）即可。
     *
     * @return 校验通过、供短事务阶段使用的载荷；返回 null 表示 ACK 跳过本次消息
     */
    protected abstract P prefetchAndValidate(P message);

    /**
     * 短事务阶段：仅本地 DB + outbox，禁止远程调用；必须以当前 eventId 做幂等。
     */
    protected abstract void persistInShortTransaction(P validated);

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }
}
