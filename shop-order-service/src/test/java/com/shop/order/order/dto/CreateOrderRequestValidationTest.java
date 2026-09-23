package com.shop.order.order.dto;

import com.shop.order.cart.dto.CartSelectRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-T 契约硬化校验矩阵（order 段）：
 * orderType 白名单 1-4（0/5/6 拒绝，5=换货显式拒绝走 POST /orders）、source 1-4、
 * items 1-100、fromCartIds ≤100、freightFen/usePointsFen 非负；CartSelectRequest.ids ≤100。
 *
 * <p>现状钉板：orderType=5 在 {@code PayTimeoutPolicy} 走 NORMAL 30min 分支不抛错，
 * 因此 5 的拒绝必须由入口 Bean Validation 白名单兜底（本测钉住）。
 */
class CreateOrderRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<String> validate(CreateOrderRequest req) {
        return validator.validate(req).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private CreateOrderRequest base() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setClientToken("token-1");
        req.setOrderType(1);
        req.setSource(1);
        req.setAddressId(9001L);
        CreateOrderRequest.Item item = new CreateOrderRequest.Item();
        item.setSkuId(11L);
        item.setQty(1);
        req.setItems(new ArrayList<>(List.of(item)));
        return req;
    }

    @Test
    void validRequest_noViolations() {
        assertThat(validate(base())).isEmpty();
        for (int t = 1; t <= 4; t++) {
            CreateOrderRequest req = base();
            req.setOrderType(t);
            assertThat(validate(req)).as("orderType=" + t).isEmpty();
        }
    }

    @Test
    void orderType_zeroFiveSix_rejected() {
        for (Integer t : new Integer[]{0, 5, 6}) {
            CreateOrderRequest req = base();
            req.setOrderType(t);
            assertThat(validate(req)).as("orderType=" + t).contains("orderType");
        }
    }

    @Test
    void source_outOfRange_rejected() {
        CreateOrderRequest req = base();
        req.setSource(0);
        assertThat(validate(req)).contains("source");
        req.setSource(5);
        assertThat(validate(req)).contains("source");
        req.setSource(9);
        assertThat(validate(req)).contains("source");
    }

    @Test
    void items_emptyAndOver100_rejected() {
        CreateOrderRequest req = base();
        req.setItems(new ArrayList<>());
        assertThat(validate(req)).contains("items");

        req.setItems(new ArrayList<>());
        for (int i = 0; i < 101; i++) {
            CreateOrderRequest.Item item = new CreateOrderRequest.Item();
            item.setSkuId((long) i);
            item.setQty(1);
            req.getItems().add(item);
        }
        assertThat(validate(req)).contains("items");
    }

    @Test
    void items_100_accepted() {
        CreateOrderRequest req = base();
        req.setItems(new ArrayList<>());
        for (int i = 0; i < 100; i++) {
            CreateOrderRequest.Item item = new CreateOrderRequest.Item();
            item.setSkuId((long) i);
            item.setQty(1);
            req.getItems().add(item);
        }
        assertThat(validate(req)).doesNotContain("items");
    }

    @Test
    void fromCartIds_over100_rejected() {
        CreateOrderRequest req = base();
        req.setFromCartIds(new ArrayList<>());
        for (long i = 0; i < 101; i++) {
            req.getFromCartIds().add(i);
        }
        assertThat(validate(req)).contains("fromCartIds");
    }

    @Test
    void negativeAmounts_rejected() {
        CreateOrderRequest req = base();
        req.setFreightFen(-1L);
        req.setUsePointsFen(-1L);
        Set<String> paths = validate(req);
        assertThat(paths).contains("freightFen", "usePointsFen");
    }

    @Test
    void cartSelectIds_over100_rejected() {
        CartSelectRequest cart = new CartSelectRequest();
        cart.setSelected(1);
        cart.setIds(new ArrayList<>());
        for (long i = 0; i < 101; i++) {
            cart.getIds().add(i);
        }
        Set<String> paths = validator.validate(cart).stream()
                .map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
        assertThat(paths).contains("ids");

        // 空 ids（全选）合法
        cart.setIds(new ArrayList<>());
        assertThat(validator.validate(cart)).isEmpty();
    }
}
