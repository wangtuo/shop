/**
 * 售后域对外码值契约（枚举常量持有类）。
 *
 * <p>本域不提供 Feign client，对外状态广播完全通过 AFTERSALE_CHANGED 事件
 * （CONTRACTS.md §3 末、§5）。
 *
 * <p>包含：{@link com.shop.api.aftersale.enums.AftersaleTypes} 售后类型、
 * {@link com.shop.api.aftersale.enums.AftersaleStatuses} 售后状态机、
 * {@link com.shop.api.aftersale.enums.ResponsibilitySide} 退货运费责任方、
 * {@link com.shop.api.aftersale.enums.ArbitrationResults} 平台仲裁结果。
 *
 * <p>核心业务规则（design.md）：
 * <ul>
 *     <li><b>8.5 商家审核时限</b>：仅退款 / 退货退款 / 换货商家 <b>2 天</b>未审核自动同意；
 *     用户寄回后商家确认收货 <b>3 天</b>超时自动确认并退款；
 *     换货商家收货后 <b>5 天</b>未发货自动转退款；</li>
 *     <li><b>8.6 运费险</b>：退货退款场景保险公司赔付退货运费，<b>最高 25 元</b>，
 *     退款成功后 <b>72 小时</b>内自动理赔到用户账户，同一订单<b>只能理赔一次</b>；</li>
 *     <li><b>8.8 价保</b>：价保期下单后 <b>7 天</b>（大促 30 天），
 *     仅限同商品降价（不含秒杀 / 拼团价），差价退原支付路径，<b>每单只能申请一次</b>；</li>
 *     <li><b>8.7 平台介入</b>：双方 3 天举证，平台 5 个工作日内终局仲裁。</li>
 * </ul>
 */
package com.shop.api.aftersale.enums;
