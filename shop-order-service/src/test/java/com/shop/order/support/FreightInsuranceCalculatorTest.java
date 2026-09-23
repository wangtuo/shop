package com.shop.order.support;

import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.order.order.dto.CreateOrderRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运费险保费计算单测（FUNDS B11）：勾选+有运费→固定配置保费；不勾选→0；
 * 配置越界（&lt;50 或 &gt;500）启动校验失败；固定保费为 design 距离/重量分级的已知残留。
 */
class FreightInsuranceCalculatorTest {

    private CreateOrderRequest request(boolean buy) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setBuyFreightInsurance(buy);
        return req;
    }

    private PriceCalcResult price(long freightFen) {
        return PriceCalcResult.builder().freightFen(freightFen).payFen(20000L + freightFen).build();
    }

    @Test
    void premium_buyWithFreight_returnsConfiguredPremium() {
        FreightInsuranceCalculator calc = new FreightInsuranceCalculator(100L);
        assertThat(calc.premiumFen(request(true), price(800L))).isEqualTo(100L);
        assertThat(calc.configuredPremiumFen()).isEqualTo(100L);
    }

    @Test
    void premium_notBought_returnsZero() {
        FreightInsuranceCalculator calc = new FreightInsuranceCalculator(100L);
        assertThat(calc.premiumFen(request(false), price(800L))).isZero();
        assertThat(calc.premiumFen(null, price(800L))).isZero()
                .describedAs("null 请求不抛错按不买处理");
        CreateOrderRequest nullFlag = new CreateOrderRequest();
        assertThat(calc.premiumFen(nullFlag, price(800L))).isZero();
    }

    @Test
    void premium_buyButFreeFreight_physicalGoodsStillInsurable() {
        // 现状无虚拟类目标识，免邮实物单仍可购险（理赔针对退货运费）
        FreightInsuranceCalculator calc = new FreightInsuranceCalculator(100L);
        assertThat(calc.premiumFen(request(true), price(0L))).isEqualTo(100L);
    }

    @Test
    void premium_boundsAccepted() {
        new FreightInsuranceCalculator(50L).validate();
        new FreightInsuranceCalculator(500L).validate();
    }

    @Test
    void premium_belowLowerBound_failsStartup() {
        FreightInsuranceCalculator calc = new FreightInsuranceCalculator(49L);
        assertThatThrownBy(calc::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void premium_aboveUpperBound_failsStartup() {
        FreightInsuranceCalculator calc = new FreightInsuranceCalculator(501L);
        assertThatThrownBy(calc::validate).isInstanceOf(IllegalStateException.class);
    }
}
