package com.shop.order.mq;

import com.shop.api.marketing.event.GroupbuyEvent;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.service.GroupbuyOrderFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GROUPBUY_EVENT 消费者单测（B1）：tag=3||4；type 1/2 落消费记录 no-op；
 * 3→onGroupSuccess、4→onGroupFail；eventId 重复直接 ACK 不进业务。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupbuyEventListenerTest {

    @Mock
    private MqConsumeService consumeService;
    @Mock
    private GroupbuyOrderFlowService flowService;

    private GroupbuyEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new GroupbuyEventListener(consumeService, flowService);
        when(consumeService.firstTime(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private GroupbuyEvent event(int type) {
        GroupbuyEvent e = GroupbuyEvent.builder()
                .activityId(8001L).groupNo("GB-1").userId(1001L)
                .orderNo("260315031001000001").type(type).requiredPeople(3).build();
        e.setEventId("evt-gb-" + type);
        return e;
    }

    @Test
    void metadata_topicGroupAndTag3Or4() {
        assertThat(listener.topic()).isEqualTo("shop_groupbuy_event");
        assertThat(listener.consumerGroup()).isEqualTo("cg_order_groupbuy");
        assertThat(listener.tag()).isEqualTo("3||4");
        assertThat(listener.type()).isEqualTo(GroupbuyEvent.class);
    }

    @Test
    void onMessage_openAndJoin_noopButConsumeRecorded() {
        listener.onMessage(event(1));
        listener.onMessage(event(2));

        // 1/2 也过了 eventId 闸门（消费记录已落），但不触发任何流转
        verify(flowService, never()).onGroupSuccess(anyString());
        verify(flowService, never()).onGroupFail(anyString());
    }

    @Test
    void onMessage_success_dispatchesByGroupNo() {
        listener.onMessage(event(3));
        verify(flowService).onGroupSuccess("GB-1");
    }

    @Test
    void onMessage_fail_dispatchesByGroupNo() {
        listener.onMessage(event(4));
        verify(flowService).onGroupFail("GB-1");
    }

    @Test
    void onMessage_duplicateEventId_ackWithoutBusiness() {
        when(consumeService.firstTime(anyString(), anyString(), anyString())).thenReturn(false);

        listener.onMessage(event(3));
        listener.onMessage(event(4));

        verify(flowService, never()).onGroupSuccess(any());
        verify(flowService, never()).onGroupFail(any());
    }
}
