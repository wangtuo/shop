/**
 * 用户域对外 Feign 客户端契约包。
 *
 * <p>定义 {@code shop-user-service} 对其他业务服务暴露的内部同步接口（路径前缀 {@code /inner/user}），
 * 方法签名与 CONTRACTS.md §3「跨域同步契约」逐字一致，Wave-2 用户服务端必须在同路径实现。
 *
 * <p>涵盖能力：
 * <ul>
 *   <li>用户/等级/收货地址查询（design.md 2.1 用户体系、2.3 收货地址）；</li>
 *   <li>积分 TCC：下单冻结、支付实扣、取消释放、退款按比例退回、场景化发放（design.md 2.2.2）；</li>
 *   <li>成长值增加（design.md 2.1.3）；</li>
 *   <li>余额/赠金账户扣款与退款入账（design.md 2.2.1，金额单位：分）。</li>
 * </ul>
 *
 * <p>本包只允许引用本域 dto 包与 shop-common，禁止引用其他 api 域包。
 */
package com.shop.api.user.client;
