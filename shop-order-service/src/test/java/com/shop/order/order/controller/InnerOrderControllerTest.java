package com.shop.order.order.controller;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;
import com.shop.order.order.service.GroupbuyOrderFlowService;
import com.shop.order.order.service.OrderQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * inner 拼团端点单测（B1）：路径必须与 shop-api {@link OrderClient} 逐字一致；
 * 三个写端点全部委托 GroupbuyOrderFlowService（与 MQ 消费同 CAS 逻辑、业务闸门幂等）。
 */
@ExtendWith(MockitoExtension.class)
class InnerOrderControllerTest {

    @Mock
    private OrderQueryService orderQueryService;
    @Mock
    private GroupbuyOrderFlowService flowService;

    private InnerOrderController controller;

    @BeforeEach
    void setUp() {
        controller = new InnerOrderController(orderQueryService, flowService);
    }

    @Test
    void classMapping_matchesFeignPrefix() {
        RequestMapping mapping = InnerOrderController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/inner/order");
    }

    @Test
    void postPaths_matchOrderClientContract() throws Exception {
        assertThat(postPath("renewGroupPayDeadline")).isEqualTo("/group/renew");
        assertThat(postPath("markGroupSucceeded")).isEqualTo("/group/succeed");
        assertThat(postPath("markGroupFailed")).isEqualTo("/group/failed");
        assertThat(postPath("listStatus")).isEqualTo("/orders/status");
    }

    private String postPath(String method) throws NoSuchMethodException {
        Class<?>[] types = switch (method) {
            case "renewGroupPayDeadline" -> new Class<?>[]{GroupPayRenewCommand.class};
            case "markGroupSucceeded" -> new Class<?>[]{GroupSucceedCommand.class};
            case "markGroupFailed" -> new Class<?>[]{GroupFailedCommand.class};
            default -> new Class<?>[]{List.class};
        };
        Method m = InnerOrderController.class.getMethod(method, types);
        return m.getAnnotation(PostMapping.class).value()[0];
    }

    @Test
    void endpoints_delegateToFlowService() {
        GroupPayRenewCommand renew = GroupPayRenewCommand.builder()
                .groupNo("G1").orderNo("N1").plusSeconds(1800L).build();
        GroupSucceedCommand succeed = GroupSucceedCommand.builder()
                .groupNo("G1").orderNo("N1").build();
        GroupFailedCommand failed = GroupFailedCommand.builder()
                .groupNo("G1").orderNo("N1").build();

        controller.renewGroupPayDeadline(renew);
        controller.markGroupSucceeded(succeed);
        controller.markGroupFailed(failed);
        controller.listStatus(List.of("N1"));

        verify(flowService).renewGroupPayDeadline(renew);
        verify(flowService).markSucceeded(succeed);
        verify(flowService).markFailed(failed);
        verify(orderQueryService).listStatus(List.of("N1"));
    }
}
