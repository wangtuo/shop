package com.shop.api.marketing.enums;

/**
 * 预售操作类型（PresaleEvent.type，design.md 4.5）。
 *
 * <p>定金支付 deposit_paid（买家原因定金不退）→ 尾款期前 final_remind 催付 → 尾款期未付 cancel 自动取消
 * （定金不退）。定金膨胀（如定金 50 抵 100）在尾款计算时体现；优惠券仅尾款阶段可用（design.md 4.2.3）。
 */
public enum PresaleOpType {

    /** 定金支付：下单付定金，登记预售单 */
    DEPOSIT_PAID(1, "定金支付"),
    /** 尾款提醒：进入尾款支付期前催付 */
    FINAL_REMIND(2, "尾款提醒"),
    /** 取消：尾款期超时未付，订单自动取消、定金不退 */
    CANCEL(3, "取消");

    private final int code;
    private final String desc;

    PresaleOpType(int code, String desc) {
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
    public static PresaleOpType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (PresaleOpType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
