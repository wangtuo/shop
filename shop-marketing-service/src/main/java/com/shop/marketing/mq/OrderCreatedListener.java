package com.shop.marketing.mq;

import com.shop.api.marketing.dto.CalcItem;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.order.event.OrderItemMessage;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.inner.MarketingAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ORDER_CREATED 消费（建单同步 TCC 之外的第二道锁）：券预核销 / 秒杀锁库存 / 拼团开团参团 / 预售定金登记。
 * eventId 幂等；失败抛异常由 Broker 重试。
 *
 * <p>W4-4/B4 修复：活动类型与活动 ID 必须显式匹配——
 * orderType=2 只认 {@code seckillActivityId}、3 只认 {@code groupbuyActivityId}、
 * 4 只认 {@code presaleActivityId}。活动单缺对应 ID 时跳过本类型锁并 P1 告警，
 * <b>绝不</b>把秒杀/拼团/预售单降级为普通单锁普通库存（同步道 {@code MarketingTxOps} 另做硬失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedListener implements MqListener<OrderCreatedEvent> {

    private final MqConsumeTemplate consumeTemplate;
    private final MarketingAppService marketingAppService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CREATED;
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_order_created";
    }

    @Override
    public Class<OrderCreatedEvent> type() {
        return OrderCreatedEvent.class;
    }

    @Override
    public void onMessage(OrderCreatedEvent e) {
        consumeTemplate.runOnce(e.getEventId(), topic(), e.getOrderNo(), () -> {
            List<CalcItem> items = new ArrayList<>();
            Long seckillActivityId = null;
            Long groupbuyActivityId = null;
            Long presaleActivityId = null;
            String groupNo = null;
            for (OrderItemMessage m : e.getItems()) {
                items.add(CalcItem.builder()
                        .skuId(m.getSkuId()).spuId(m.getSpuId()).merchantId(m.getMerchantId())
                        .shopId(m.getShopId()).category3Id(m.getCategory3Id())
                        .qty(m.getQty()).salePriceFen(m.getSalePriceFen()).build());
                if (m.getSeckillActivityId() != null) {
                    seckillActivityId = m.getSeckillActivityId();
                }
                if (m.getGroupbuyActivityId() != null) {
                    groupbuyActivityId = m.getGroupbuyActivityId();
                }
                if (m.getPresaleActivityId() != null) {
                    presaleActivityId = m.getPresaleActivityId();
                }
                if (m.getGroupNo() != null) {
                    groupNo = m.getGroupNo();
                }
            }

            int orderType = e.getOrderType() == null ? 1 : e.getOrderType();
            // 显式按订单类型映射对应活动 ID，缺 ID 跳过本类型锁（告警 + ACK，毒丸数据不重试轰炸）
            Long activityId = switch (orderType) {
                case 2 -> seckillActivityId;
                case 3 -> groupbuyActivityId;
                case 4 -> presaleActivityId;
                default -> null;
            };
            if ((orderType == 2 || orderType == 3 || orderType == 4) && activityId == null) {
                log.error("[P1告警] 活动订单缺少对应活动 ID，跳过活动锁且不按普通单处理 orderNo={} orderType={}"
                                + " seckillId={} groupbuyId={} presaleId={}",
                        e.getOrderNo(), orderType, seckillActivityId, groupbuyActivityId, presaleActivityId);
                return;
            }

            List<Long> couponIds = new ArrayList<>();
            if (e.getUserCouponId() != null) {
                couponIds.add(e.getUserCouponId());
            }
            marketingAppService.lock(PromotionLockCommand.builder()
                    .userId(e.getUserId())
                    .orderNo(e.getOrderNo())
                    .orderType(orderType)
                    .items(items)
                    .userCouponIds(couponIds)
                    .activityId(activityId)
                    .groupbuyActivityId(orderType == 3 ? groupbuyActivityId : null)
                    .groupNo(orderType == 3 ? groupNo : null)
                    .build());
        });
    }
}
