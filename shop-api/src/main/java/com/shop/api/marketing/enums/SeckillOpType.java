package com.shop.api.marketing.enums;

/**
 * 秒杀库存操作类型（SeckillEvent.type / 秒杀库存锁定状态流转，CONTRACTS.md §4 库存锁定状态）。
 *
 * <p>下单 lock（可售→锁定）→ 支付成功 deduct（锁定→占用）→ 取消/超时 release（锁定→可售）。
 * 秒杀与所有其他优惠互斥（design.md 4.2.3）。
 */
public enum SeckillOpType {

    /** 锁定：下单成功预占秒杀库存 */
    LOCK(1, "锁定"),
    /** 扣减：支付成功，锁定转占用/物理扣减 */
    DEDUCT(2, "扣减"),
    /** 释放：订单取消/超时，锁定回可售 */
    RELEASE(3, "释放");

    private final int code;
    private final String desc;

    SeckillOpType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 按码值反查枚举，未知码值返回 {@code null}。 */
    public static SeckillOpType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (SeckillOpType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
