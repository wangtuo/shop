/**
 * 营销域码值枚举（CONTRACTS.md §4 统一状态码，与库表注释、MQ 事件 type 字段一致）。
 *
 * <ul>
 *   <li>{@code ActivityTypes}：营销活动类型 1 满减 2 满折 3 满赠 4 第N件 5 限时折扣
 *       10 秒杀 11 拼团 12 预售 13 砍价 14 抽奖；</li>
 *   <li>{@code CouponTypes}：1 满减券 2 折扣券 3 无门槛券 4 免邮券 5 品类券 6 店铺券；</li>
 *   <li>{@code CouponStatuses}：0 未使用 1 已使用 2 已过期 3 已作废（design.md 4.3 生命周期）；</li>
 *   <li>{@code CouponIssueWays}：1 主动领取 2 活动发放 3 新人礼包 4 系统补偿 5 积分兑换（design.md 4.3.1）；</li>
 *   <li>{@code SeckillOpType}/{@code GroupbuyOpType}/{@code PresaleOpType}：
 *       秒杀（1 锁定 2 扣减 3 释放）、拼团（1 开团 2 参团 3 成团 4 失败）、
 *       预售（1 定金支付 2 尾款提醒 3 取消）事件操作类型；</li>
 *   <li>{@code PromotionLayer}：4.2.1 叠加层级 1 商品级 2 店铺级 3 品类券 4 店铺券 5 平台券 6 积分。</li>
 * </ul>
 *
 * <p>互斥规则（design.md 4.2.3）：秒杀（PRODUCT 层）互斥一切；同层券 1 张；满减满折互斥；
 * 拼团跳过券层与积分层；预售仅尾款阶段进入券层。
 */
package com.shop.api.marketing.enums;
