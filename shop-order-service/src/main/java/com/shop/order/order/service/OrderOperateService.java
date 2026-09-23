package com.shop.order.order.service;

import com.shop.order.order.dto.AddressUpdateRequest;
import com.shop.order.order.dto.ShipRequest;

/**
 * 订单操作服务（design 5.2 / 5.3.3）。
 */
public interface OrderOperateService {

    /** 用户取消待付款订单（发 ORDER_CANCELLED；已支付订单不允许直接取消，走售后）。 */
    void cancel(String orderNo, Long userId);

    /** 支付超时系统取消（延时消息/扫描任务调用，cancelType=2）。 */
    void timeoutCancel(String orderNo);

    /**
     * B1：拼团失败系统关单（GROUPBUY_EVENT 失败 / inner /group/failed 调用，cancelType=4）。
     * 与超时取消同一路径：仅待付款单可关，非待付款幂等忽略；支付域成功态拦截防「钱已收单已关」；
     * ORDER_CANCELLED 走既有 outbox 释放库存等资源（拼团不允许券/积分）。
     */
    void groupFailCancel(String orderNo);

    /** 商家发货（20→30，发 ORDER_SHIPPED + 自动收货延时）。 */
    void ship(String orderNo, Long merchantId, ShipRequest request);

    /** 用户确认收货（30→40，发 ORDER_CONFIRMED + 售后期结束延时）。 */
    void confirm(String orderNo, Long userId);

    /** 自动确认收货（延时消息/扫描任务调用）。 */
    void autoConfirm(String orderNo);

    /** 售后期满关闭并发 ORDER_COMPLETED（延时消息/扫描任务调用）。 */
    void closeAftersaleWindow(String orderNo);

    /** 待发货修改收货地址。 */
    void updateAddress(String orderNo, Long userId, AddressUpdateRequest request);

    /** 提醒发货（仅标记，不重复通知）。 */
    void remindShip(String orderNo, Long userId);

    /** 删除订单（仅已取消/已关闭）。 */
    void delete(String orderNo, Long userId);

    /** 重新购买：订单明细回填购物车。 */
    void rebuy(String orderNo, Long userId);
}
