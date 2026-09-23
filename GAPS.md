# 契约缺口台账（协调者集中修复，代理不得自行改 shop-api/common/framework）

## #1 用户服务报告（已完成，99 测试绿）
- [G1-1] RefundSucceededEvent 无积分字段 → 设计上由售后域调 UserClient.refundPoints（确认 aftersale 实现即可，不改事件）
- [G1-2] GrantPointsCommand(userId/bizNo/points)、GrowthCommand(userId/biz/scene)、AmountCommand 缺 @NotNull → 集中补 Bean Validation 注解
- [G1-3] PaymentSucceededEvent.amountFen 可空 → 评估改 long 原始类型或消费方防御（消费方已防御，低优）

## #2 支付服务报告（已完成，38 测试绿）
- [G2-1] CreatePaymentCommand 只有单 payMethod，无法表达组合支付多手段/好友代付 → 给 Command 增加可选 parts/friendUserId（纯追加、向后兼容），Feign 通道打通
- [G2-2] RefundSucceededEvent 单 payMethod，混合退款多渠道拆分无法表达 → 评估追加可选 splits 列表（待清算/售后报告确认是否需要按渠道冲正）
- [G2-3] 对账/差错无 api 模块 DTO（跨域无人调用，仅平台 HTTP）→ 可接受
- 遗留：对账单 mock 文件；好友代付人校验链路未做（契约无字段）

## #3 商品服务报告（已完成，148 测试绿，核心覆盖 89%）
- [G3-1] OrderDTO 无完成时间字段，15 天评价窗口暂用 updateTime/payTime 兜底 → shop-api 订单 DTO 追加 finishTime（追加字段），订单域落库填充
- [G3-2] 追评图片未持久化（只有主评 images 列）→ 商品域补 append_images 列 + 落库
- PaymentSucceededEvent 无 items：已有本地锁定流水幂等兜底，可接受


## #4 清算服务报告（已完成，89 测试绿，核心覆盖 85.9%）
- [G4-1] PaymentSucceededEvent 缺 merchantId 与金额构成（商品/运费/店铺优惠/平台券/积分）→ 现以 OrderClient 按 orderNo 回查补全（回查失败 MQ 重试），事件自包含增强为可选项，当前方案可接受
- [G4-2] OrderConfirmedEvent 无优惠前商品总额 → 服务内反算，可接受
- [G4-3] 90 天清退缺售后纠纷查询接口 → 以 90 天观察期为无纠纷口径；可在售后域补 inner 查询（低优，Wave3 评估）

## #5 售后服务报告（已完成，52 测试绿）
- [G5-1] 订单事件/DTO 无"是否购运费险/保费"字段 → 默认无、保费 0、理赔封顶 25 元；E2E 阶段订单建单若需运费险再评估追加
- [G5-2] 无类目质保天数数据源 → 统一默认 15 天，可接受（商品类目 attr_template 可扩展）
- [G5-3] OrderShipped/Confirmed 事件无积分数量（仅 pointsDeductFen）→ 按抵扣金额比例退，口径一致可接受
- [G5-4] 退货地址无下发通道（事件/Client 无地址字段）→ 自动同意退货的地址推送未落地；Wave3 评估事件追加 returnAddress（可选）
- [G5-5] ~~ProductClient.returnStock 无责任方~~ → 核实不构成缺口：AftersaleChangedEvent 已含 responsibilitySide+items，商品域消费事件按商家责任→残次仓/买家→可售 回库（与商品实现一致）
- [G5-6] 价保活动价无专用契约 → 以请求 bigPromotion + 普通价口径实现，design"活动价排除"由调用方保证；可接受

## #6 营销服务报告（已完成，68 测试绿）
- [G6-1] ~~OrderCreatedEvent 单 userCouponId 丢失三券叠加~~ → 核实影响有限：Feign 链路已完备——PriceCalcCommand 有 category/shop/platformCouponId 三个字段、PromotionLockCommand 有 List<Long> userCouponIds；下单锁定走 Feign 不依赖该事件。事件体可后续追加 List（向后兼容），低优
- [G6-2] 试算无商品/店铺实时快照（依赖上送字段）→ 调用方（订单确认页）上送，符合契约 CalcItem 设计，可接受
- [G6-3] orderType=5 换货营销语义未定义 → 按普通单处理，合理
- [G6-4] GROUPBUY_EVENT SUCCESS 后订单转待发货由订单域负责 → 待 #11 订单报告交叉确认
- JaCoCo 门禁：多模块已各自加插件（pay/settlement），统一门禁可在 Wave3 收口
