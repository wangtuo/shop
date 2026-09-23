package com.shop.order.mq;

import com.shop.api.marketing.event.GroupbuyEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.service.GroupbuyOrderFlowService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * GROUPBUY_EVENT 消费者（cg_order_groupbuy，B1）：
 * broker 侧 tag 订阅 {@code 3||4}（3 成团 / 4 失败）；防御性兜底处理 1 开团/2 参团——
 * 仅落 t_order_mq_consume 消费记录后 no-op。
 *
 * <p>双幂等：eventId 闸门（本 listener）+ 业务键 {@code gbflow:groupNo#type}
 * （{@link GroupbuyOrderFlowService}），兼容生产端「每团员一事件」与「一团一事件」两种形态——
 * 消费按 groupNo 回查全团，不依赖事件体 orderNo 覆盖全团。
 */
@Component
@RequiredArgsConstructor
public class GroupbuyEventListener implements MqListener<GroupbuyEvent> {

    private static final Logger log = LoggerFactory.getLogger(GroupbuyEventListener.class);

    /** 3 成团、4 失败两个 tag（RocketMQ tag 过滤表达式）。 */
    public static final String TAG_SUCCESS_OR_FAIL = "3||4";

    private final MqConsumeService consumeService;
    private final GroupbuyOrderFlowService flowService;

    @Override
    public String topic() {
        return MqTopics.GROUPBUY_EVENT;
    }

    @Override
    public String tag() {
        return TAG_SUCCESS_OR_FAIL;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_groupbuy";
    }

    @Override
    public Class<GroupbuyEvent> type() {
        return GroupbuyEvent.class;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(GroupbuyEvent event) {
        if (event == null) {
            return;
        }
        // eventId 第一道闸门（框架 EventNormalizer 已保证非空），与业务处理同事务
        if (!consumeService.firstTime(event.getEventId(), topic(), event.getGroupNo())) {
            return;
        }
        Integer type = event.getType();
        if (type == null) {
            log.warn("拼团事件缺少 type，消费记录已落，忽略 groupNo={} eventId={}",
                    event.getGroupNo(), event.getEventId());
            return;
        }
        switch (type) {
            case 1, 2 -> // 开团/参团：订单侧无动作，消费记录已落即完成
                    log.debug("拼团开团/参团事件 no-op groupNo={} orderNo={} type={}",
                            event.getGroupNo(), event.getOrderNo(), type);
            case 3 -> flowService.onGroupSuccess(event.getGroupNo());
            case 4 -> flowService.onGroupFail(event.getGroupNo());
            default -> log.warn("未知拼团事件类型，消费记录已落，忽略 groupNo={} type={}",
                    event.getGroupNo(), type);
        }
    }
}
