package com.shop.common.exception;

import lombok.Getter;

/**
 * 全系统统一错误码段：
 * <pre>
 * 0        成功
 * 1xxxxx   通用 / 网关 / 鉴权
 * 2xxxxx   用户域
 * 3xxxxx   商品域
 * 4xxxxx   营销域
 * 5xxxxx   订单域
 * 6xxxxx   支付域
 * 7xxxxx   清算域
 * 8xxxxx   售后域
 * </pre>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    PARAM_INVALID(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    FORBIDDEN(10003, "无权限访问"),
    NOT_FOUND(10004, "资源不存在"),
    CONFLICT(10005, "请求冲突，请刷新后重试"),
    REPEAT_SUBMIT(10006, "请勿重复提交"),
    TOO_MANY_REQUESTS(10007, "请求过于频繁，请稍后再试"),
    DEPENDENCY_FAIL(10008, "依赖服务异常"),
    SYSTEM_ERROR(10009, "系统繁忙，请稍后再试"),
    DEPENDENCY_TIMEOUT(10010, "依赖服务调用超时"),

    STOCK_NOT_ENOUGH(30001, "库存不足"),
    GOODS_NOT_SALE(30002, "商品不可售"),

    ACTIVITY_NOT_AVAILABLE(40001, "活动不可用或已结束"),
    COUPON_NOT_AVAILABLE(40002, "优惠券不可用"),
    COUPON_LIMIT(40003, "优惠券已领完或超出限领数量"),
    POINTS_NOT_ENOUGH(40004, "积分余额不足"),

    ORDER_NOT_FOUND(50001, "订单不存在"),
    ORDER_STATUS_ERROR(50002, "订单状态不允许该操作"),
    CART_LIMIT(50003, "购物车商品数量超过上限"),
    LIMIT_PURCHASE(50004, "超出限购数量"),

    PAY_ERROR(60001, "支付失败"),
    PAY_SIGN_ERROR(60002, "支付回调验签失败"),
    PAY_DUPLICATE(60003, "支付回调重复处理"),
    REFUND_AMOUNT_ERROR(60004, "退款金额非法或超出可退金额"),

    SETTLE_AMOUNT_ERROR(70001, "结算金额异常"),
    DEPOSIT_NOT_ENOUGH(70002, "商户保证金余额不足"),
    WITHDRAW_LIMIT(70003, "不满足提现条件"),

    AFTERSALE_NOT_ALLOW(80001, "当前订单状态不允许申请售后"),
    AFTERSALE_STATUS_ERROR(80002, "售后单状态不允许该操作"),
    AFTERSALE_AMOUNT_EXCEED(80003, "累计退款金额超出实付金额");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
