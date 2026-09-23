package com.shop.marketing.mq;

import com.shop.api.marketing.enums.PresaleOpType;
import com.shop.api.marketing.event.PresaleEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.activity.service.PresaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PRESALE_EVENT 自消费：定金登记时投递的尾款超时取消延时消息到达后，
 * 单条取消预售单（与 PresaleFinalJob 定时扫描构成双保险，条件更新天然幂等）。
 */
@Component
@RequiredArgsConstructor
public class PresaleEventListener implements MqListener<PresaleEvent> {

    private final MqConsumeTemplate consumeTemplate;
    private final PresaleService presaleService;

    @Override
    public String topic() {
        return MqTopics.PRESALE_EVENT;
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_presale_cancel";
    }

    @Override
    public Class<PresaleEvent> type() {
        return PresaleEvent.class;
    }

    @Override
    public void onMessage(PresaleEvent e) {
        if (e.getType() == null || e.getType() != PresaleOpType.CANCEL.getCode()) {
            return;
        }
        // C15：延时消息体未自带 eventId 时取框架归一化结果（keys=bizKey，含 R4-25 的 #final
        // 轮次后缀），保证延时行与取消后补发的即时 CANCEL 事件各自独立幂等、均被处理。
        String eventId = (e.getEventId() == null || e.getEventId().isBlank())
                ? MqConsumeContext.currentEventId() : e.getEventId();
        consumeTemplate.runOnce(eventId, topic(), e.getOrderNo(),
                () -> presaleService.cancelByOrderNo(e.getOrderNo()));
    }
}
