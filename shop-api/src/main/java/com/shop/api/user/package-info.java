/**
 * 用户域对外契约根包（对应服务 {@code shop-user-service}，schema {@code shop_user}，Redis DB 0）。
 *
 * <p>本包按 {@code client / dto / event / enums} 四个子包组织用户域对其他服务暴露的全部跨域能力：
 * <ul>
 *   <li>{@code client}：内部 Feign 接口（{@code /inner/user}）——用户/等级/收货地址查询，
 *       积分冻结/实扣/释放/退回/发放，成长值增加，余额与赠金账户扣款/入账；</li>
 *   <li>{@code dto}：上述接口的请求命令与返回 DTO，金额一律 Long（分），字段自包含；</li>
 *   <li>{@code event}：积分变动事件（Topic {@code POINTS_CHANGED}）；</li>
 *   <li>{@code enums}：用户类型、账户状态、会员等级权益、账户类型、积分场景/变动类型、成长值场景码值。</li>
 * </ul>
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包（{@code com.shop.api.product} 等），
 * 只允许引用 {@code com.shop.common}；Feign 方法签名以 CONTRACTS.md §3 为准，码值以 §4 为准。
 */
package com.shop.api.user;
