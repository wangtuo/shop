/**
 * 订单域对外契约根包（对应服务 {@code shop-order-service}，schema {@code shop_order}，Redis DB 3）。
 *
 * <p>本包按 {@code client / dto / event / enums} 四个子包组织订单域对其他服务暴露的全部跨域能力：
 * <ul>
 *   <li>{@code client}：内部 Feign 接口（{@code /inner/order}）——按订单号查询订单聚合 getByOrderNo；</li>
 *   <li>{@code dto}：订单聚合、订单明细、收货快照、发票 DTO，金额一律 Long（分）；</li>
 *   <li>{@code event}：订单创建/取消/发货/确认收货/完成事件
 *       （Topic {@code ORDER_CREATED}/{@code ORDER_CANCELLED}/{@code ORDER_SHIPPED}/
 *       {@code ORDER_CONFIRMED}/{@code ORDER_COMPLETED}）及订单明细消息体；</li>
 *   <li>{@code enums}：订单类型、订单状态、取消类型、订单来源、明细售后状态码值。</li>
 * </ul>
 *
 * <p>订单号规则见 CONTRACTS.md §6（18 位：YYMMDD+业务类型2位+用户ID后4位+6位序列）；
 * 下单链路 TCC 逆序补偿与支付超时见 CONTRACTS.md §5、design 5.3。
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包，只允许引用 {@code com.shop.common}；
 * Feign 方法签名以 CONTRACTS.md §3 为准，码值以 §4 为准。
 */
package com.shop.api.order;
