package com.shop.product.comment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.product.event.CommentCreatedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.comment.dto.CommentAppendRequest;
import com.shop.product.comment.dto.CommentCreateRequest;
import com.shop.product.comment.dto.CommentReplyRequest;
import com.shop.product.comment.entity.ProductComment;
import com.shop.product.comment.mapper.ProductCommentMapper;
import com.shop.product.comment.service.SensitiveWordService;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.goods.mapper.ProductSpuMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价域：15 天时效、三维度好评率、字数图片视频校验、重复评价、追评/回复约束、敏感词联动。
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private ProductCommentMapper commentMapper;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ProductSpuMapper spuMapper;
    @Mock
    private OrderClient orderClient;
    @Mock
    private SensitiveWordService sensitiveWordService;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private CommentServiceImpl commentService;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER = 2L;
    private static final Long MERCHANT_ID = 7L;
    private static final Long SPU_ID = 200L;
    private static final Long SKU_ID = 100L;
    private static final String ORDER_NO = "260916010001000001";
    private static final String CONTENT = "商品质量很好物流快客服态度也好值得回购";

    @BeforeEach
    void setUp() {
        lenient().when(sensitiveWordService.checkAndFilter(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(idGenerator.nextIdString()).thenReturn("123456789012345678");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- helpers ----

    private CommentCreateRequest createRequest(int quality, int logistics, int service) {
        CommentCreateRequest req = new CommentCreateRequest();
        req.setOrderNo(ORDER_NO);
        req.setSpuId(SPU_ID);
        req.setSkuId(SKU_ID);
        req.setQualityStar(quality);
        req.setLogisticsStar(logistics);
        req.setServiceStar(service);
        req.setContent(CONTENT);
        return req;
    }

    private OrderDTO order(Long userId, int status, LocalDateTime finishTime) {
        OrderDTO order = new OrderDTO();
        order.setOrderNo(ORDER_NO);
        order.setUserId(userId);
        order.setStatus(status);
        order.setUpdateTime(finishTime);
        return order;
    }

    private ProductSku sku() {
        ProductSku sku = new ProductSku();
        sku.setId(SKU_ID);
        sku.setSpuId(SPU_ID);
        sku.setMerchantId(MERCHANT_ID);
        return sku;
    }

    private void stubCompletedOrder(LocalDateTime finishTime) {
        when(orderClient.getByOrderNo(ORDER_NO))
                .thenReturn(Result.success(order(USER_ID, 40, finishTime)));
    }

    private void mockCreateDependencies() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(commentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    }

    // ---------------- 发表评价：时效 ----------------

    @Test
    void 完成15天内评价_成功落库且计好评() {
        stubCompletedOrder(LocalDateTime.now().minusDays(5));
        mockCreateDependencies();

        Long id = commentService.create(createRequest(5, 5, 4), USER_ID);

        ArgumentCaptor<ProductComment> captor = ArgumentCaptor.forClass(ProductComment.class);
        verify(commentMapper).insert(captor.capture());
        ProductComment saved = captor.getValue();
        assertEquals("CM123456789012345678", saved.getCommentNo());
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(MERCHANT_ID, saved.getMerchantId());
        // 综合 (5+5+4)/3 ≈ 4.67 → 5，计入好评
        verify(spuMapper).increaseCommentCount(SPU_ID, 1);
    }

    @Test
    void 超过15天_拒绝评价() {
        stubCompletedOrder(LocalDateTime.now().minusDays(20));

        BizException ex = assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(commentMapper, never()).insert(any());
    }

    @Test
    void 刚好15天内_允许评价() {
        stubCompletedOrder(LocalDateTime.now().minusDays(15).plusHours(1));
        mockCreateDependencies();

        commentService.create(createRequest(5, 5, 5), USER_ID);

        verify(commentMapper).insert(any());
    }

    @Test
    void 完成时间在未来_拒绝评价() {
        stubCompletedOrder(LocalDateTime.now().plusDays(1));

        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
    }

    @Test
    void updateTime缺失时退化支付时间_超期拒绝() {
        OrderDTO dto = order(USER_ID, 40, null);
        dto.setPayTime(LocalDateTime.now().minusDays(30));
        when(orderClient.getByOrderNo(ORDER_NO)).thenReturn(Result.success(dto));

        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
    }

    @Test
    void updateTime缺失时退化支付时间_期内允许() {
        OrderDTO dto = order(USER_ID, 40, null);
        dto.setPayTime(LocalDateTime.now().minusDays(2));
        when(orderClient.getByOrderNo(ORDER_NO)).thenReturn(Result.success(dto));
        mockCreateDependencies();

        commentService.create(createRequest(5, 5, 5), USER_ID);

        verify(commentMapper).insert(any());
    }

    // ---------------- 发表评价：订单校验 ----------------

    @Test
    void 订单未完成_拒绝评价() {
        when(orderClient.getByOrderNo(ORDER_NO))
                .thenReturn(Result.success(order(USER_ID, 30, LocalDateTime.now().minusDays(1))));

        BizException ex = assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void 评价他人订单_禁止() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));

        BizException ex = assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), OTHER_USER));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void 订单服务异常_转依赖失败() {
        when(orderClient.getByOrderNo(ORDER_NO)).thenThrow(new RuntimeException("feign timeout"));

        BizException ex = assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    @Test
    void 订单不存在_拒绝评价() {
        when(orderClient.getByOrderNo(ORDER_NO)).thenReturn(Result.fail(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
    }

    @Test
    void 未登录_拒绝评价() {
        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), null));
    }

    // ---------------- 发表评价：商品/重复/媒体/敏感词 ----------------

    @Test
    void SKU不存在_拒绝评价() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        when(skuMapper.selectById(SKU_ID)).thenReturn(null);

        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
    }

    @Test
    void 同一订单同一SKU重复评价_拒绝() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(commentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
        assertEquals(ErrorCode.REPEAT_SUBMIT.getCode(), ex.getCode());
        verify(commentMapper, never()).insert(any());
    }

    @Test
    void 图片超过9张_拒绝() {
        CommentCreateRequest req = createRequest(5, 5, 5);
        req.setImages(new ArrayList<>(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")));

        BizException ex = assertThrows(BizException.class, () -> commentService.create(req, USER_ID));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(orderClient, never()).getByOrderNo(anyString());
    }

    @Test
    void 视频超过30秒_拒绝() {
        CommentCreateRequest req = createRequest(5, 5, 5);
        req.setVideoUrl("https://cdn.example.com/v.mp4");
        req.setVideoDurationSec(31);

        assertThrows(BizException.class, () -> commentService.create(req, USER_ID));
    }

    @Test
    void 视频刚好30秒_允许() {
        CommentCreateRequest req = createRequest(5, 5, 5);
        req.setVideoUrl("https://cdn.example.com/v.mp4");
        req.setVideoDurationSec(30);
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        mockCreateDependencies();

        commentService.create(req, USER_ID);

        verify(commentMapper).insert(any());
    }

    @Test
    void 命中硬违禁词_拒绝落库() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        mockCreateDependencies();
        when(sensitiveWordService.checkAndFilter(anyString()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "内容包含违禁敏感词"));

        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
        verify(commentMapper, never()).insert(any());
        verify(spuMapper, never()).increaseCommentCount(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------------- 好评率 ----------------

    @Test
    void 综合4星_计入好评() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        mockCreateDependencies();

        // (3+4+4)/3 ≈ 3.67 → 四舍五入 4
        commentService.create(createRequest(3, 4, 4), USER_ID);

        verify(spuMapper).increaseCommentCount(SPU_ID, 1);
    }

    @Test
    void 综合3星_不计好评() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        mockCreateDependencies();

        // (3+3+4)/3 ≈ 3.33 → 3
        commentService.create(createRequest(3, 3, 4), USER_ID);

        verify(spuMapper).increaseCommentCount(SPU_ID, 0);
    }

    @Test
    void 综合星级计算_四舍五入() {
        assertEquals(5, CommentServiceImpl.overallStar(5, 5, 5));
        assertEquals(1, CommentServiceImpl.overallStar(1, 1, 1));
        assertEquals(4, CommentServiceImpl.overallStar(5, 3, 3));
        assertEquals(3, CommentServiceImpl.overallStar(3, 3, 3));
        assertEquals(4, CommentServiceImpl.overallStar(4, 4, 4));
    }

    // ---------------- 追评 ----------------

    private ProductComment existingComment() {
        ProductComment c = new ProductComment();
        c.setId(900L);
        c.setUserId(USER_ID);
        c.setMerchantId(MERCHANT_ID);
        c.setSpuId(SPU_ID);
        c.setSkuId(SKU_ID);
        c.setCreateTime(LocalDateTime.now().minusDays(10));
        return c;
    }

    private CommentAppendRequest appendRequest() {
        CommentAppendRequest req = new CommentAppendRequest();
        req.setContent("用了十天再来追评确实不错很满意");
        return req;
    }

    @Test
    void 追评_180天内首次_成功() {
        when(commentMapper.selectById(900L)).thenReturn(existingComment());

        commentService.append(900L, appendRequest(), USER_ID);

        ArgumentCaptor<ProductComment> captor = ArgumentCaptor.forClass(ProductComment.class);
        verify(commentMapper).updateById(captor.capture());
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getAppendTime());
        assertEquals("用了十天再来追评确实不错很满意", captor.getValue().getAppendContent());
    }

    @Test
    void 追评非本人评价_禁止() {
        when(commentMapper.selectById(900L)).thenReturn(existingComment());

        assertThrows(BizException.class,
                () -> commentService.append(900L, appendRequest(), OTHER_USER));
        verify(commentMapper, never()).updateById(any());
    }

    @Test
    void 已追评过_拒绝第二次() {
        ProductComment c = existingComment();
        c.setAppendTime(LocalDateTime.now().minusDays(1));
        when(commentMapper.selectById(900L)).thenReturn(c);

        BizException ex = assertThrows(BizException.class,
                () -> commentService.append(900L, appendRequest(), USER_ID));
        assertEquals(ErrorCode.REPEAT_SUBMIT.getCode(), ex.getCode());
    }

    @Test
    void 超过180天_拒绝追评() {
        ProductComment c = existingComment();
        c.setCreateTime(LocalDateTime.now().minusDays(200));
        when(commentMapper.selectById(900L)).thenReturn(c);

        assertThrows(BizException.class,
                () -> commentService.append(900L, appendRequest(), USER_ID));
    }

    @Test
    void 追评图片超过9张_拒绝() {
        when(commentMapper.selectById(900L)).thenReturn(existingComment());
        CommentAppendRequest req = appendRequest();
        req.setImages(new ArrayList<>(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")));

        assertThrows(BizException.class, () -> commentService.append(900L, req, USER_ID));
    }

    @Test
    void 追评不存在的评价_抛NOT_FOUND() {
        when(commentMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class,
                () -> commentService.append(404L, appendRequest(), USER_ID));
    }

    // ---------------- 商家回复 ----------------

    private CommentReplyRequest replyRequest() {
        CommentReplyRequest req = new CommentReplyRequest();
        req.setContent("感谢亲的支持欢迎再次光临小店");
        return req;
    }

    private void loginAsMerchant(Long merchantId) {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(merchantId).build());
    }

    @Test
    void 本店商户首次回复_成功() {
        loginAsMerchant(MERCHANT_ID);
        when(commentMapper.selectById(900L)).thenReturn(existingComment());

        commentService.reply(900L, replyRequest());

        ArgumentCaptor<ProductComment> captor = ArgumentCaptor.forClass(ProductComment.class);
        verify(commentMapper).updateById(captor.capture());
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getReplyTime());
    }

    @Test
    void 非本店商户回复_禁止() {
        loginAsMerchant(8L);
        when(commentMapper.selectById(900L)).thenReturn(existingComment());

        BizException ex = assertThrows(BizException.class,
                () -> commentService.reply(900L, replyRequest()));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void 已回复过_拒绝第二次() {
        loginAsMerchant(MERCHANT_ID);
        ProductComment c = existingComment();
        c.setReplyTime(LocalDateTime.now().minusDays(1));
        when(commentMapper.selectById(900L)).thenReturn(c);

        BizException ex = assertThrows(BizException.class,
                () -> commentService.reply(900L, replyRequest()));
        assertEquals(ErrorCode.REPEAT_SUBMIT.getCode(), ex.getCode());
    }

    @Test
    void 未登录商户_回复被拒() {
        assertThrows(BizException.class, () -> commentService.reply(900L, replyRequest()));
        verify(commentMapper, never()).selectById(any());
    }

    // ---------------- C-COMMENT：评价/晒单事件生产（outbox 同事务登记） ----------------

    /** insert 为 mock，手动回填主键，模拟 MP 雪花回填。 */
    private void mockInsertAssignId() {
        doAnswer(inv -> {
            inv.getArgument(0, ProductComment.class).setId(123L);
            return 1;
        }).when(commentMapper).insert(any(ProductComment.class));
    }

    private CommentCreatedEvent verifySingleEvent(String expectedBizNo) {
        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> bizCap = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher, times(1)).publish(topicCap.capture(),
                org.mockito.ArgumentMatchers.isNull(), payloadCap.capture(), bizCap.capture());
        assertEquals(MqTopics.COMMENT_CREATED, topicCap.getValue());
        assertEquals(expectedBizNo, bizCap.getValue());
        assertTrue(payloadCap.getValue() instanceof CommentCreatedEvent);
        return (CommentCreatedEvent) payloadCap.getValue();
    }

    @Test
    void 无图评价_outbox恰好一条_behavior1_withImageFalse() {
        stubCompletedOrder(LocalDateTime.now().minusDays(5));
        mockCreateDependencies();
        mockInsertAssignId();

        commentService.create(createRequest(5, 5, 4), USER_ID);

        CommentCreatedEvent event = verifySingleEvent("CM123456789012345678#b1");
        assertEquals(1, event.getBehaviorType());
        assertEquals(Boolean.FALSE, event.getWithImage());
        assertEquals(123L, event.getCommentId());
        assertEquals(ORDER_NO, event.getOrderNo());
        assertEquals(USER_ID, event.getUserId());
        assertEquals(SPU_ID, event.getSpuId());
        assertEquals(SKU_ID, event.getSkuId());
        org.junit.jupiter.api.Assertions.assertNotNull(event.getEventTime());
    }

    @Test
    void 带图评价_behavior仍为1_withImageTrue() {
        CommentCreateRequest req = createRequest(5, 5, 4);
        req.setImages(new ArrayList<>(List.of("https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg")));
        stubCompletedOrder(LocalDateTime.now().minusDays(5));
        mockCreateDependencies();
        mockInsertAssignId();

        commentService.create(req, USER_ID);

        CommentCreatedEvent event = verifySingleEvent("CM123456789012345678#b1");
        assertEquals(1, event.getBehaviorType());
        assertEquals(Boolean.TRUE, event.getWithImage());
    }

    @Test
    void 主评被屏蔽_append晒单不发布事件() {
        ProductComment c = existingComment();
        c.setStatus(2);
        c.setCommentNo("CM123456789012345678");
        c.setOrderNo(ORDER_NO);
        when(commentMapper.selectById(900L)).thenReturn(c);
        CommentAppendRequest req = appendRequest();
        req.setImages(new ArrayList<>(List.of("https://cdn.example.com/s.jpg")));

        commentService.append(900L, req, USER_ID);

        // 追评内容仍更新，但 status=2 不发、不补发事件
        verify(commentMapper).updateById(any(ProductComment.class));
        verify(outboxPublisher, never()).publish(anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void 重复评价被REPEAT_SUBMIT拦截_无事件() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku());
        when(commentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(BizException.class,
                () -> commentService.create(createRequest(5, 5, 5), USER_ID));
        verify(outboxPublisher, never()).publish(anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void 好评率更新失败_事件不落地() {
        stubCompletedOrder(LocalDateTime.now().minusDays(2));
        mockCreateDependencies();
        mockInsertAssignId();
        doThrow(new RuntimeException("好评率更新失败模拟回滚"))
                .when(spuMapper).increaseCommentCount(SPU_ID, 1);

        assertThrows(RuntimeException.class,
                () -> commentService.create(createRequest(5, 5, 4), USER_ID));
        // 发布点位于好评率更新之后：失败即不登记 outbox（容器内随事务整体回滚）
        verify(outboxPublisher, never()).publish(anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void append晒单_发布behavior2_重复追评不重复发() {
        ProductComment c = existingComment();
        c.setStatus(1);
        c.setCommentNo("CM123456789012345678");
        c.setOrderNo(ORDER_NO);
        when(commentMapper.selectById(900L)).thenReturn(c);
        CommentAppendRequest req = appendRequest();
        req.setImages(new ArrayList<>(List.of("https://cdn.example.com/s1.jpg")));

        commentService.append(900L, req, USER_ID);

        CommentCreatedEvent event = verifySingleEvent("CM123456789012345678#b2");
        assertEquals(2, event.getBehaviorType());
        assertEquals(Boolean.TRUE, event.getWithImage());
        assertEquals(900L, event.getCommentId());

        // 同一评价第二次追评走 REPEAT_SUBMIT 拦截：事件总数仍为 1（幂等）
        assertThrows(BizException.class, () -> commentService.append(900L, appendRequest(), USER_ID));
        verify(outboxPublisher, times(1)).publish(anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void 主评后追评_outbox键带行为维度不撞唯一键() {
        // 同一行评论：主评事务登记 #b1，追评事务登记 #b2——
        // R4-25 前两者都是裸 commentNo，追评必撞 uk_topic_tag_bizkey 回滚（mock 掩盖了 UK，故显式回归）
        stubCompletedOrder(LocalDateTime.now().minusDays(5));
        mockCreateDependencies();
        mockInsertAssignId();

        commentService.create(createRequest(5, 5, 4), USER_ID);

        ProductComment c = existingComment();
        c.setStatus(1);
        c.setCommentNo("CM123456789012345678");
        c.setOrderNo(ORDER_NO);
        when(commentMapper.selectById(900L)).thenReturn(c);
        commentService.append(900L, appendRequest(), USER_ID);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher, times(2)).publish(anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(), keyCap.capture());
        assertEquals(List.of("CM123456789012345678#b1", "CM123456789012345678#b2"),
                keyCap.getAllValues());
        // 消息体 bizNo 始终是裸 commentNo（下游 COMMENT:/SHOW: 口径不变）
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher, times(2)).publish(anyString(), org.mockito.ArgumentMatchers.isNull(),
                payloadCap.capture(), org.mockito.ArgumentMatchers.anyString());
        assertEquals("CM123456789012345678",
                ((CommentCreatedEvent) payloadCap.getAllValues().get(0)).getBizNo());
        assertEquals("CM123456789012345678",
                ((CommentCreatedEvent) payloadCap.getAllValues().get(1)).getBizNo());
    }
}
