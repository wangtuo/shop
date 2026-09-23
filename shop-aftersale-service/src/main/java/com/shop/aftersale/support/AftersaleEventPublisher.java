package com.shop.aftersale.support;

import com.shop.api.aftersale.dto.AftersaleItemMessage;
import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.aftersale.aftersale.entity.AftersaleItem;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.common.constant.MqTopics;
import com.shop.framework.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 售后状态变更事件发布（AFTERSALE_CHANGED）。
 *
 * <p>P1-1：走 transactional outbox。所有调用方（{@code AftersaleServiceImpl.change/apply}、
 * {@code AftersaleMqServiceImpl.onRefundSuccess}）均在 {@code @Transactional} 事务内、
 * 状态条件更新成功之后调用本方法，事件与售后单状态同提交/回滚。</p>
 */
@Component
@RequiredArgsConstructor
public class AftersaleEventPublisher {

    private final OutboxPublisher outboxPublisher;

    public void publish(AftersaleOrder o, Integer oldStatus, Integer newStatus,
                        String logisticsNo, List<AftersaleItem> items, long transitionLogId) {
        List<AftersaleItemMessage> messages = items.stream()
                .map(i -> AftersaleItemMessage.builder()
                        .orderItemId(i.getOrderItemId())
                        .skuId(i.getSkuId())
                        .qty(i.getQty())
                        .refundFen(i.getRefundFen())
                        .build())
                .toList();
        AftersaleChangedEvent event = AftersaleChangedEvent.builder()
                .aftersaleNo(o.getAftersaleNo())
                .orderNo(o.getOrderNo())
                .userId(o.getUserId())
                .merchantId(o.getMerchantId())
                .type(o.getType())
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .refundFen(o.getRefundFen() == null ? 0L : o.getRefundFen())
                .responsibilitySide(o.getResponsibilitySide())
                .logisticsNo(logisticsNo)
                .items(messages)
                .build();
        event.setBizNo(o.getAftersaleNo());
        // R4-25：同一售后单会两次进入同一状态（拒绝后修改重提回到待审核 10；仲裁买家胜诉
        // 回到待换货发货 41；同一轮内 10→55 后又 80→55 等）。outbox uk(topic,tag,biz_key)
        // 行投递后永不删除，bizKey 以【每次流转唯一的状态流水 id】作后缀，任何两次状态变更
        // 都不可能撞三元组。消息体 bizNo 仍为裸 aftersaleNo——两个下游订阅组（cg_order_aftersale
        // / cg_product_aftersale）的幂等与业务判断只用消息体字段，不读 MQ keys（已全量核实）。
        outboxPublisher.publish(MqTopics.AFTERSALE_CHANGED, String.valueOf(newStatus), event,
                o.getAftersaleNo() + "#t" + transitionLogId);
    }
}
