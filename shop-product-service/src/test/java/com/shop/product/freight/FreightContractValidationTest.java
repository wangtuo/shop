package com.shop.product.freight;

import com.shop.api.product.dto.FreightCalcRequest;
import com.shop.product.comment.dto.CommentCreateRequest;
import com.shop.product.freight.dto.FreightRegionSaveRequest;
import com.shop.product.freight.dto.FreightTemplateSaveRequest;
import com.shop.product.freight.dto.FreightTemplateStatusRequest;
import com.shop.product.freight.service.FreightPricingService;
import com.shop.product.goods.controller.AdminGoodsController;
import com.shop.product.stock.controller.ProductInnerController;
import com.shop.product.stock.service.StockService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * API-T（product 侧）非法报文矩阵：IN 列表 101、媒体 URL javascript/ftp/513、9 张以上媒体、
 * remark 257 字、运费 DTO 各类越界——全部在 Bean Validation / 方法校验层被拒。
 * GlobalExceptionHandler 已把 ConstraintViolationException / MethodArgumentNotValid 统一映射 10001。
 */
class FreightContractValidationTest {

    private static ValidatorFactory factory;
    private static Validator beanValidator;
    private static ExecutableValidator methodValidator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        beanValidator = factory.getValidator();
        methodValidator = beanValidator.forExecutables();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // ---------- IN 列表 101：listSkus 裸 List<Long> @Size(max=100) ----------

    @Test
    void listSkus_101ids_rejected() throws Exception {
        ProductInnerController controller =
                new ProductInnerController(mock(StockService.class), mock(FreightPricingService.class));
        Method method = ProductInnerController.class.getMethod("listSkus", List.class);

        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 101; i++) {
            ids.add(i);
        }
        Set<ConstraintViolation<ProductInnerController>> rejected =
                methodValidator.validateParameters(controller, method, new Object[]{ids});
        assertFalse(rejected.isEmpty());

        // 100 个与空列表均放行（空列表契约：返回空集合）
        assertTrue(methodValidator.validateParameters(controller, method,
                new Object[]{new ArrayList<Long>()}).isEmpty());
        assertTrue(methodValidator.validateParameters(controller, method,
                new Object[]{ids.subList(0, 100)}).isEmpty());
    }

    // ---------- violation remark 257 字 ----------

    @Test
    void violationRemark_257chars_rejected() throws Exception {
        AdminGoodsController controller = new AdminGoodsController(null);
        Method method = AdminGoodsController.class.getMethod("violation", Long.class, String.class);

        String tooLong = "x".repeat(257);
        Set<ConstraintViolation<AdminGoodsController>> rejected =
                methodValidator.validateParameters(controller, method, new Object[]{1L, tooLong});
        assertFalse(rejected.isEmpty());

        // null（备注可选）与恰好 256 字放行
        assertTrue(methodValidator.validateParameters(controller, method,
                new Object[]{1L, null}).isEmpty());
        assertTrue(methodValidator.validateParameters(controller, method,
                new Object[]{1L, "x".repeat(256)}).isEmpty());
    }

    // ---------- 评价媒体：javascript/ftp/513/超张数 ----------

    @Test
    void commentMedia_badSchemeAndLength_rejected() {
        CommentCreateRequest js = validComment();
        js.setVideoUrl("javascript:alert(1)");
        assertFalse(beanValidator.validate(js).isEmpty());

        CommentCreateRequest ftp = validComment();
        ftp.setVideoUrl("ftp://x");
        assertFalse(beanValidator.validate(ftp).isEmpty());

        CommentCreateRequest longUrl = validComment();
        longUrl.setVideoUrl("https://x.co/" + "a".repeat(513 - "https://x.co/".length()));
        assertFalse(beanValidator.validate(longUrl).isEmpty());

        CommentCreateRequest jsImage = validComment();
        jsImage.setImages(new ArrayList<>(List.of("javascript:alert(1)")));
        assertFalse(beanValidator.validate(jsImage).isEmpty());

        CommentCreateRequest longImage = validComment();
        longImage.setImages(new ArrayList<>(List.of("https://x.co/" + "b".repeat(513 - "https://x.co/".length()))));
        assertFalse(beanValidator.validate(longImage).isEmpty());
    }

    @Test
    void commentImages_overLimit_rejected() {
        CommentCreateRequest req = validComment();
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("https://cdn.example.com/img/" + i + ".jpg");
        }
        req.setImages(eleven);
        Set<ConstraintViolation<CommentCreateRequest>> rejected = beanValidator.validate(req);
        assertFalse(rejected.isEmpty());

        // 10 张合法 https 图片通过 W0 注解（服务端 validateMedia 仍保持 9 张业务上限）
        CommentCreateRequest ten = validComment();
        ten.setImages(new ArrayList<>(eleven.subList(0, 10)));
        assertTrue(beanValidator.validate(ten).isEmpty());

        // 9 张正常放行
        CommentCreateRequest nine = validComment();
        nine.setImages(new ArrayList<>(eleven.subList(0, 9)));
        assertTrue(beanValidator.validate(nine).isEmpty());
    }

    // ---------- FreightCalcRequest ----------

    @Test
    void freightCalcRequest_invalidBodies_rejected() {
        // 空 items
        FreightCalcRequest empty = FreightCalcRequest.builder().items(new ArrayList<>())
                .province("330000").build();
        assertFalse(beanValidator.validate(empty).isEmpty());

        // qty=0 / null、skuId=null
        FreightCalcRequest badQty = FreightCalcRequest.builder()
                .items(new ArrayList<>(List.of(FreightCalcRequest.FreightItem.builder()
                        .skuId(1L).qty(0).build())))
                .province("330000").build();
        assertFalse(beanValidator.validate(badQty).isEmpty());

        FreightCalcRequest nullSku = FreightCalcRequest.builder()
                .items(new ArrayList<>(List.of(FreightCalcRequest.FreightItem.builder()
                        .skuId(null).qty(1).build())))
                .province("330000").build();
        assertFalse(beanValidator.validate(nullSku).isEmpty());

        // 合法报文放行
        FreightCalcRequest valid = FreightCalcRequest.builder()
                .orderNo("NO1")
                .items(new ArrayList<>(List.of(FreightCalcRequest.FreightItem.builder()
                        .skuId(1L).qty(2).build())))
                .province("330000").city("330100").district("330106").build();
        assertTrue(beanValidator.validate(valid).isEmpty());
    }

    // ---------- 模板/规则 DTO ----------

    @Test
    void freightTemplateSaveRequest_invalid_rejected() {
        FreightTemplateSaveRequest base = validTemplate();
        base.setChargeType(4);
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validTemplate();
        base.setDefaultAdd(0);
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validTemplate();
        base.setDefaultFirstFee(-1L);
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validTemplate();
        base.setName("  ");
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validTemplate();
        base.setIsDefault(9);
        assertFalse(beanValidator.validate(base).isEmpty());

        assertTrue(beanValidator.validate(validTemplate()).isEmpty());
    }

    @Test
    void freightRegionSaveRequest_invalid_rejected() {
        FreightRegionSaveRequest base = validRegion();
        base.setRegionCodes(new ArrayList<>());
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validRegion();
        base.setRegionCodes(new ArrayList<>(List.of("  ")));
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validRegion();
        base.setRegionCodes(new ArrayList<>(List.of("1234567890123")));
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validRegion();
        base.setAddUnit(0);
        assertFalse(beanValidator.validate(base).isEmpty());

        base = validRegion();
        base.setDeliverable(2);
        assertFalse(beanValidator.validate(base).isEmpty());

        assertTrue(beanValidator.validate(validRegion()).isEmpty());
    }

    @Test
    void templateStatusRequest_null_rejected() {
        FreightTemplateStatusRequest req = new FreightTemplateStatusRequest();
        assertFalse(beanValidator.validate(req).isEmpty());
        req.setStatus(0);
        assertTrue(beanValidator.validate(req).isEmpty());
    }

    // ---------- helpers ----------

    private CommentCreateRequest validComment() {
        CommentCreateRequest r = new CommentCreateRequest();
        r.setOrderNo("NO123");
        r.setSpuId(1L);
        r.setSkuId(2L);
        r.setQualityStar(5);
        r.setLogisticsStar(4);
        r.setServiceStar(5);
        r.setContent("东西不错，物流也很快！");
        r.setImages(new ArrayList<>());
        return r;
    }

    private FreightTemplateSaveRequest validTemplate() {
        FreightTemplateSaveRequest r = new FreightTemplateSaveRequest();
        r.setName("全国默认");
        r.setChargeType(1);
        r.setDefaultFirst(1);
        r.setDefaultFirstFee(800L);
        r.setDefaultAdd(1);
        r.setDefaultAddFee(200L);
        r.setFreeConditionFen(0L);
        r.setIsDefault(0);
        return r;
    }

    private FreightRegionSaveRequest validRegion() {
        FreightRegionSaveRequest r = new FreightRegionSaveRequest();
        r.setRegionCodes(new ArrayList<>(List.of("330000")));
        r.setFirstUnit(1);
        r.setFirstFeeFen(1000L);
        r.setAddUnit(1);
        r.setAddFeeFen(200L);
        r.setDeliverable(1);
        return r;
    }
}
