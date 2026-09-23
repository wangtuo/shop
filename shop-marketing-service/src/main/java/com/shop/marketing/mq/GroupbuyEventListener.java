package com.shop.marketing.mq;

import com.shop.api.marketing.enums.GroupbuyOpType;
import com.shop.api.marketing.event.GroupbuyEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.activity.groupbuy.service.GroupbuyEventAcceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 拼团事件消费（B1）：仅订阅 tag 3 成团 / 4 失败（RocketMQ 标签表达式 {@code 3||4}）；
 * 1 开团/2 参团无订单侧动作，broker 不投递，代码内再防御性 no-op。
 *
 * <p>消费组 {@code cg_marketing_groupbuy}。双幂等：MqConsumeTemplate 的 eventId 流水
 * + t_groupbuy_event_todo（uk_event_id 与 groupNo+orderNo+opType 业务键）。
 * 调订单域 C25 失败时受理服务落 todo=2 并抛异常交 MQ 重试，补偿 Job 扫表兜底，不吞单不伪成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupbuyEventListener implements MqListener<GroupbuyEvent> {

    private final MqConsumeTemplate consumeTemplate;
    private final GroupbuyEventAcceptService acceptService;

    @Override
    public String topic() {
        return MqTopics.GROUPBUY_EVENT;
    }

    @Override
    public String tag() {
        // 仅成团/失败触发订单域动作；1||2 不订阅（单测另对代码守卫双验）
        return GroupbuyOpType.SUCCESS.getCode() + "||" + GroupbuyOpType.FAIL.getCode();
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_groupbuy";
    }

    @Override
    public Class<GroupbuyEvent> type() {
        return GroupbuyEvent.class;
    }

    @Override
    public void onMessage(GroupbuyEvent e) {
        Integer type = e.getType();
        if (type == null || (type != GroupbuyOpType.SUCCESS.getCode() && type != GroupbuyOpType.FAIL.getCode())) {
            // 1 开团 / 2 参团落消费记录后 no-op：无订单状态动作（若 tag 表达式被放宽也不改行为）
            if (type != null) {
                consumeTemplate.runOnce(e.getEventId(), topic(), e.getOrderNo(), () ->
                        log.debug("拼团事件 type={} 无需订单动作 eventId={}", type, e.getEventId()));
            }
            return;
        }
        consumeTemplate.runOnce(e.getEventId(), topic(), e.getOrderNo(), () -> acceptService.accept(e));
    }
}
