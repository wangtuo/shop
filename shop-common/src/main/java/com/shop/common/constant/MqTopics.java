package com.shop.common.constant;

/**
 * RocketMQ Topic 统一注册中心，所有服务只允许引用此处常量。
 * 命名：shop_{域}_{动作}，Tag 使用各域自定义常量。
 */
public final class MqTopics {

    private MqTopics() {
    }

    /** 订单已创建（下单成功、库存已锁） */
    public static final String ORDER_CREATED = "shop_order_created";
    /** 订单取消（用户取消/超时未支付） */
    public static final String ORDER_CANCELLED = "shop_order_cancelled";
    /** 订单支付成功（支付域发出，订单/库存/营销/积分/清算共同消费） */
    public static final String ORDER_PAID = "shop_order_paid";
    /** 订单已发货 */
    public static final String ORDER_SHIPPED = "shop_order_shipped";
    /** 确认收货（用户确认/超时自动） */
    public static final String ORDER_CONFIRMED = "shop_order_confirmed";
    /** 订单完成（售后期结束） */
    public static final String ORDER_COMPLETED = "shop_order_completed";
    /** 订单域内部：支付超时延时消息（仍待付款则取消，与超时扫描 Job 构成双保险） */
    public static final String ORDER_PAY_TIMEOUT = "shop_order_pay_timeout";
    /** 订单域内部：发货后自动确认收货延时消息（10 天） */
    public static final String ORDER_AUTO_CONFIRM = "shop_order_auto_confirm";
    /** 订单域内部：确认收货后售后期结束延时消息（15 天） */
    public static final String ORDER_AFTERSALE_WINDOW = "shop_order_aftersale_window";
    /** 售后域内部：商家审核超时 / 运费险 72h 理赔延时消息 */
    public static final String AFTERSALE_TIMEOUT = "shop_aftersale_timeout";
    /** 支付单状态变更（渠道回调原始结果） */
    public static final String PAY_RESULT = "shop_pay_result";
    /** 退款成功（支付域发出） */
    public static final String REFUND_SUCCESS = "shop_refund_success";
    /** 库存预警 */
    public static final String STOCK_WARNING = "shop_stock_warning";
    /** 支付成功后登记待清算 */
    public static final String CLEARING_REGISTER = "shop_clearing_register";
    /** 到达结算时点（确认收货），生成结算单 */
    public static final String CLEARING_SETTLE = "shop_clearing_settle";
    /** 退款触发清算冲正 */
    public static final String CLEARING_REVERSE = "shop_clearing_reverse";
    /** 售后单状态变更 */
    public static final String AFTERSALE_CHANGED = "shop_aftersale_changed";
    /** 秒杀活动事件 */
    public static final String SECKILL_EVENT = "shop_seckill_event";
    /** 拼团事件（开团/参团/成团/失败） */
    public static final String GROUPBUY_EVENT = "shop_groupbuy_event";
    /** 预售事件（定金支付/尾款到期） */
    public static final String PRESALE_EVENT = "shop_presale_event";
    /** 积分变更事件 */
    public static final String POINTS_CHANGED = "shop_points_changed";
    /** 提现结果事件 */
    public static final String WITHDRAW_RESULT = "shop_withdraw_result";
    /** 保证金预警事件 */
    public static final String DEPOSIT_ALERT = "shop_deposit_alert";
    /** 退款冲正三档扣尽仍不足的挂起缺口告警（结算域发出，P1-10） */
    public static final String REFUND_SHORTFALL = "shop_refund_shortfall";
    /** 用户注册成功事件（用户域发出，营销新人礼包等消费） */
    public static final String USER_REGISTERED = "shop_user_registered";
    /** 评价/晒单创建事件（商品域审核通过后发出，用户成长体系消费） */
    public static final String COMMENT_CREATED = "shop_comment_created";

    // 注意：ORDER_PAID 上的 PaymentSucceededEvent.payScene 仅作消费场景守卫（1/2/3 商品 4 保证金），
    // topic 字面量与场景码值无关，不为任何 scene 新增 topic（FUNDS C10）。

    /** 延时等级（RocketMQ 5.x 代理使用秒级 deliverAfter） */
    public static final long DELAY_15_MIN_SECONDS = 15 * 60L;
    public static final long DELAY_30_MIN_SECONDS = 30 * 60L;
    public static final long DELAY_24_HOUR_SECONDS = 24 * 3600L;
    public static final long DELAY_10_DAY_SECONDS = 10 * 24 * 3600L;
    public static final long DELAY_15_DAY_SECONDS = 15 * 24 * 3600L;
}
