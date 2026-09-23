/**
 * 支付域对外契约根包（对应服务 {@code shop-pay-service}，schema {@code shop_pay}，Redis DB 4）。
 *
 * <p>本包按 {@code client / dto / event / enums} 四个子包组织支付域对其他服务暴露的全部跨域能力：
 * <ul>
 *   <li>{@code client}：内部 Feign 接口（{@code /inner/pay}）——创建支付单 createPayment、
 *       按支付单号查询 getByPayNo、发起退款 refund；</li>
 *   <li>{@code dto}：支付/退款命令与支付单、退款单 DTO，金额一律 Long（分）；</li>
 *   <li>{@code event}：支付成功事件（Topic {@code ORDER_PAID}）、退款成功事件（Topic {@code REFUND_SUCCESS}）；</li>
 *   <li>{@code enums}：支付方式、终端、支付单/退款单状态、退款类型/来源、对账差异类型码值。</li>
 * </ul>
 *
 * <p>核心约束（design 6.2/6.4）：渠道异步回调先验签再幂等落单，跨域扇出全部走 MQ；
 * 退款原路退回，混合支付按占比分摊，多次部分退款累计不超过实付金额。
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包，只允许引用 {@code com.shop.common}；
 * Feign 方法签名以 CONTRACTS.md §3 为准，码值以 §4 为准。
 */
package com.shop.api.pay;
