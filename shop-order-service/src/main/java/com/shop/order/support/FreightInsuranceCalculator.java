package com.shop.order.support;

import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.order.order.dto.CreateOrderRequest;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 运费险保费计算（FUNDS B11）。
 *
 * <p><b>已知残留（design 8.6）：</b>design 规定保费 0.5-5 元「按距离和重量」分级，
 * 当前模型无收货距离服务、商品重量/体积字段也未参与运费模板定价（SkuDTO.weightGram 有字段
 * 但无距离数据源），故本期以配置 {@code shop.order.freight-insurance.premium-fen}
 * 统一固定保费（默认 100 分 = 1 元），启动时校验 50≤x≤500，越界 fail-fast。
 * 真实分级定价依赖商品中心重量字段与物流距离服务，属后续缺口；类目黑白名单（虚拟商品不可购）
 * 当前无虚拟类目标识，暂不拦截，后续补。
 */
@Component
public class FreightInsuranceCalculator {

    /** 保费下限（分，0.5 元）。 */
    public static final long MIN_PREMIUM_FEN = 50L;
    /** 保费上限（分，5 元）。 */
    public static final long MAX_PREMIUM_FEN = 500L;

    private final long premiumFen;

    public FreightInsuranceCalculator(
            @Value("${shop.order.freight-insurance.premium-fen:100}") long premiumFen) {
        this.premiumFen = premiumFen;
    }

    /** 启动校验：保费配置越界直接失败，拒绝带错误配置上线。 */
    @PostConstruct
    void validate() {
        if (premiumFen < MIN_PREMIUM_FEN || premiumFen > MAX_PREMIUM_FEN) {
            throw new IllegalStateException("运费险保费配置非法 shop.order.freight-insurance.premium-fen="
                    + premiumFen + "，必须在 [" + MIN_PREMIUM_FEN + ", " + MAX_PREMIUM_FEN + "] 分内");
        }
    }

    /**
     * 计算本单运费险保费。
     *
     * @param req   建单请求（buyFreightInsurance null/false 不买）
     * @param price 服务端营销试算结果（freightFen 已为产品域权威值）
     * @return 保费（分）；不勾选返回 0。保费不可被券/积分抵扣，由调用方直接计入 payFen
     */
    public long premiumFen(CreateOrderRequest req, PriceCalcResult price) {
        if (req == null || !Boolean.TRUE.equals(req.getBuyFreightInsurance())) {
            return 0L;
        }
        long freightFen = price == null || price.getFreightFen() == null ? 0L : price.getFreightFen();
        // 仅实物/有运费订单开放：当前商品模型无虚拟类目标识，所有商品按实物处理；
        // freightFen=0（满额包邮/默认 0 运费）的实物单仍可购险（理赔针对退货运费）。
        // 未来引入虚拟类目黑名单时在此短路返回 0。
        if (freightFen < 0) {
            return 0L;
        }
        return premiumFen;
    }

    /** 配置保费值（测试/上层组装读取）。 */
    public long configuredPremiumFen() {
        return premiumFen;
    }
}
