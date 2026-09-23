package com.shop.user.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.constant.SecurityHeaders;
import com.shop.framework.web.AuthInterceptor;
import com.shop.framework.web.GlobalExceptionHandler;
import com.shop.user.share.controller.ShareController;
import com.shop.user.share.dto.ShareCompleteRequest;
import com.shop.user.share.dto.ShareResultVO;
import com.shop.user.share.service.ShareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分享回调控制器（B6-b/C44）：未携带 X-User-Id 一律 401（AuthInterceptor）；
 * 登录态 + 合法 body 透传服务；参数校验失败 400。
 */
@ExtendWith(MockitoExtension.class)
class ShareControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ShareService shareService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ShareController(shareService))
                .addInterceptors(new AuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String body(String requestNo, int targetType, String targetId) throws Exception {
        ShareCompleteRequest req = new ShareCompleteRequest();
        req.setRequestNo(requestNo);
        req.setTargetType(targetType);
        req.setTargetId(targetId);
        return objectMapper.writeValueAsString(req);
    }

    @Test
    void 未登录_无XUserId头_401拒绝() throws Exception {
        mockMvc.perform(post("/users/shares")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-no-auth", 1, "spu-1")))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(shareService);
    }

    @Test
    void 已登录_合法body_透传服务并返回积分() throws Exception {
        when(shareService.complete(eq(1001L), any(ShareCompleteRequest.class)))
                .thenReturn(ShareResultVO.builder().requestNo("req-ok").pointsEarned(10L).build());

        mockMvc.perform(post("/users/shares")
                        .header(SecurityHeaders.USER_ID, "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-ok", 1, "spu-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestNo").value("req-ok"))
                .andExpect(jsonPath("$.data.pointsEarned").value(10));

        ArgumentCaptor<ShareCompleteRequest> captor = ArgumentCaptor.forClass(ShareCompleteRequest.class);
        verify(shareService).complete(eq(1001L), captor.capture());
        assertEquals("req-ok", captor.getValue().getRequestNo());
        assertEquals(1, captor.getValue().getTargetType());
    }

    @Test
    void 已登录_空requestNo_参数校验400() throws Exception {
        mockMvc.perform(post("/users/shares")
                        .header(SecurityHeaders.USER_ID, "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", 1, "spu-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
        org.mockito.Mockito.verifyNoInteractions(shareService);
    }

    @Test
    void 已登录_targetType越界_参数校验400() throws Exception {
        mockMvc.perform(post("/users/shares")
                        .header(SecurityHeaders.USER_ID, "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("req-bad-type", 99, "spu-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }
}
