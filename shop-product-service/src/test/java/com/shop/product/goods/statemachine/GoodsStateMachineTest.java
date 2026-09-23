package com.shop.product.goods.statemachine;

import com.shop.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品八态状态机：全部合法边 + 典型非法边 + 终态/同态/非法码。
 */
class GoodsStateMachineTest {

    private final GoodsStateMachine machine = new GoodsStateMachine();

    @Test
    void 全部合法流转_应当放行() {
        // 草稿 → 待审核 →（通过）已上架
        assertDoesNotThrow(() -> machine.checkTransition(0, 1));
        assertDoesNotThrow(() -> machine.checkTransition(1, 3));
        // 待审核 →（拒绝）审核拒绝 → 修改 → 草稿 / 重新提交
        assertDoesNotThrow(() -> machine.checkTransition(1, 2));
        assertDoesNotThrow(() -> machine.checkTransition(2, 0));
        assertDoesNotThrow(() -> machine.checkTransition(2, 1));
        // 上架/下架互转、上架 → 售罄、售罄 → 补货上架、售罄 → 手动下架
        assertDoesNotThrow(() -> machine.checkTransition(3, 4));
        assertDoesNotThrow(() -> machine.checkTransition(4, 3));
        assertDoesNotThrow(() -> machine.checkTransition(3, 5));
        assertDoesNotThrow(() -> machine.checkTransition(5, 3));
        assertDoesNotThrow(() -> machine.checkTransition(5, 4));
        // 违规下架：待审核/在售/下架/售罄均可
        assertDoesNotThrow(() -> machine.checkTransition(1, 6));
        assertDoesNotThrow(() -> machine.checkTransition(3, 6));
        assertDoesNotThrow(() -> machine.checkTransition(4, 6));
        assertDoesNotThrow(() -> machine.checkTransition(5, 6));
        // 删除
        assertDoesNotThrow(() -> machine.checkTransition(0, 7));
        assertDoesNotThrow(() -> machine.checkTransition(2, 7));
        assertDoesNotThrow(() -> machine.checkTransition(3, 7));
        assertDoesNotThrow(() -> machine.checkTransition(4, 7));
        assertDoesNotThrow(() -> machine.checkTransition(5, 7));
    }

    @Test
    void 非法流转_应当抛BizException() {
        // 草稿不能直接上架/下架/售罄/违规
        assertThrows(BizException.class, () -> machine.checkTransition(0, 3));
        assertThrows(BizException.class, () -> machine.checkTransition(0, 5));
        // 审核拒绝不能直接上架
        assertThrows(BizException.class, () -> machine.checkTransition(2, 3));
        // 已下架不能直接到售罄（售罄只能由在售库存驱动）
        assertThrows(BizException.class, () -> machine.checkTransition(4, 5));
        // 终态：违规下架/已删除不能再流转
        assertThrows(BizException.class, () -> machine.checkTransition(6, 3));
        assertThrows(BizException.class, () -> machine.checkTransition(6, 7));
        assertThrows(BizException.class, () -> machine.checkTransition(7, 3));
        // 已上架不能回到待审核/草稿/拒绝
        assertThrows(BizException.class, () -> machine.checkTransition(3, 1));
        assertThrows(BizException.class, () -> machine.checkTransition(3, 0));
        assertThrows(BizException.class, () -> machine.checkTransition(3, 2));
    }

    @Test
    void 同态流转_应当拒绝() {
        assertThrows(BizException.class, () -> machine.checkTransition(3, 3));
        assertFalse(machine.canTransit(3, 3));
    }

    @Test
    void 非法状态码_应当拒绝() {
        assertThrows(IllegalArgumentException.class, () -> machine.checkTransition(99, 3));
        assertFalse(machine.canTransit(99, 3));
        assertFalse(machine.canTransit(3, 99));
    }

    @Test
    void canTransit_合法边返回true() {
        assertTrue(machine.canTransit(0, 1));
        assertTrue(machine.canTransit(5, 3));
    }
}
