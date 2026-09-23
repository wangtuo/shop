package com.shop.marketing.mq;

import com.shop.api.user.event.UserRegisteredEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.gift.NewUserGiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户注册事件消费（卡 B6）：触发新人礼包自动发券（只发券，不发积分——积分归 user 域）。
 *
 * <p>幂等三层：{@link MqConsumeTemplate#runOnce} eventId 流水 + requestNo
 * {@code NEWUSER:{userId}:{couponId}} + CouponService NEW_USER countHeld。
 * 未知异常抛出，由框架按可恢复策略重试。</p>
 */
@Component
@RequiredArgsConstructor
public class UserRegisteredListener implements MqListener<UserRegisteredEvent> {

    private final MqConsumeTemplate consumeTemplate;
    private final NewUserGiftService newUserGiftService;

    @Override
    public String topic() {
        return MqTopics.USER_REGISTERED;
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_user_registered";
    }

    @Override
    public Class<UserRegisteredEvent> type() {
        return UserRegisteredEvent.class;
    }

    @Override
    public void onMessage(UserRegisteredEvent e) {
        consumeTemplate.runOnce(e.getEventId(), topic(), String.valueOf(e.getUserId()),
                () -> newUserGiftService.issueGift(e.getUserId()));
    }
}
