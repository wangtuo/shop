/**
 * 支付域数据传输对象包：支付 / 退款的创建命令与查询结果。
 *
 * <p>所有金额字段均为 {@code Long}，单位分，禁止 double/float（CONTRACTS.md §2.2）；
 * 状态与类型字段为 Integer 码值，定义见 {@link com.shop.api.pay.enums}。</p>
 *
 * <p>退款语义（design 6.4）：退款一律原路退回（第三方渠道回原渠道，余额退回余额账户），
 * 混合支付按各支付方式实付占比分摊退回；支持按订单明细多次部分退款，累计不超过实付金额，
 * 优惠按分摊比例处理（优惠券不退回、积分按比例退回）。</p>
 */
package com.shop.api.pay.dto;
