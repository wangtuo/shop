/**
 * 用户域常量类包：用户类型、账户状态、会员等级、账户类型、积分场景/变动类型、成长值场景。
 *
 * <p>全部为 {@code final class} + {@code private} 构造的 int 常量类（{@code public static final int}），
 * 不使用枚举，以便与库表 TINYINT/INT 状态码直接对应。
 *
 * <p>关键取值：
 * <ul>
 *   <li>用户类型 -1 游客 / 0 普通 / 1 商户 / 2 平台运营（design.md 2.1.1）；</li>
 *   <li>会员等级 L0-L4，成长区间 0-99/100-999/1000-4999/5000-19999/20000+，
 *       折扣 1.00/0.98/0.95/0.92/0.90，积分倍率 1/1.1/1.5/2/3（design.md 2.1.2，{@code MemberLevels} 提供换算工具）；</li>
 *   <li>账户类型 1 余额 2 赠金 3 积分 4 优惠券（design.md 2.2.1）；</li>
 *   <li>积分获取场景与成长值场景（design.md 2.2.2、2.1.3）。</li>
 * </ul>
 */
package com.shop.api.user.enums;
