# 功能完备性审计报告（对照 design.md V2.0）

- 审计日期：2026-09-17｜审计方式：只读，逐项核对 `shop-*-service/src/main`（辅以 shop-api/shop-framework/shop-common/sql/deploy）
- 图例：✅已实现（有完整业务链路）｜⚠️部分实现（主链路在，有明确缺项）｜❌缺失（无代码/仅空实体）
- 证据路径相对仓库根；行号经 grep/Read 核实。

## 一、用户模块（12 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-USER-1 | 四类用户/注册/JWT 登录/失败锁定 | ✅ | UserTypes.java:11-20（-1/0/1/2）；AuthServiceImpl.java:75,79-80,115；LoginLockService.java:28-29 | 游客(-1)仅枚举常量，无游客会话 |
| F-USER-2 | L0-L4 等级、成长值区间、折扣/积分倍率 | ✅ | MemberLevels.java:38,44-59,70-78；GrowthServiceImpl.java:55,71；PriceEngine.java:128-129 | — |
| F-USER-3 | 成长值：消费1:1/评价+10/晒单+20/连签7天+50/年末80%折算保级 | ⚠️ | PointsCalc.java:89-97,62-64,118-134；GrowthDiscountJob.java:34；UserPointsMqServiceImpl.java:80-88 | 评价+10、晒单+20 无任何触发方（GrowthScene.COMMENT/SHOW_ORDER 零调用） |
| F-USER-4 | 余额/赠金/积分/券四类账户 | ⚠️ | UserAccount.java:10-20；AccountServiceImpl.java:82-86,112,134；AccountTypes.java:11-20 | 券账户在营销侧；余额无充值入口；赠金只有扣减无入账 |
| F-USER-5 | 积分四场景获取+日上限 | ⚠️ | PointsCalc.java:26-35,53-59,77-86；AccountServiceImpl.java:378-390 | 评价/分享/晒单发分只有计算器与上限，无业务入口调用 |
| F-USER-6 | 积分365天过期/抵现100:1封顶50%/商城兑换/抽奖 | ⚠️ | PointsExpireJob.java:26；AccountServiceImpl.java:158-161,421-465；OrderCreateServiceImpl.java:225-232 | 积分商城兑换、积分抽奖均未接通（无 C 端接口、不扣积分） |
| F-USER-7 | 收货地址：20 上限/1 默认/全字段/标签 | ⚠️ | AddressServiceImpl.java:24,36-42,54-65；UserAddress.java:19-37 | 缺"地址变更验证手机号"（无验证码环节） |
| F-USER-8 | 账户流水/积分冻结(TCC)/发放批次 | ✅ | UserAccountFlow.java:15-42；UserPointsFreeze.java:16-36；AccountServiceImpl.java:145-267,393-413 | — |
| F-USER-9 | 支付发分/取消退分/退款按比例退分 | ✅ | OrderPaidListener.java:22-38；OrderCancelledListener.java:21-38；AftersaleMqServiceImpl.java:160-168→AccountServiceImpl.java:271-304 | 退款退分走售后 Feign（非 MQ），能力闭环 |
| F-USER-10 | 手机脱敏/BCrypt/密钥强度 fail-fast | ✅ | PhoneMaskingSerializer.java:14-17,46-52；AuthServiceImpl.java:75；ShopSecretEnvironmentValidator.java:43-61 | UserPrivacyUtils 为死代码（可清理） |
| F-USER-11 | 新人礼包（注册自动发券） | ❌ | 仅 CouponIssueWays.java:12-13 NEW_USER 枚举；AuthServiceImpl.register:55-92 无营销调用 | 无用户注册事件、无任何发券接线 |
| F-USER-12 | 连续签到/补签 | ⚠️ | SignInServiceImpl.java:75-93,104-111；SignInController.java:22-25 | 无补签功能 |

## 二、商品模块（16 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-PROD-1 | SPU：名称/品牌/类目/主图/轮播/详情/属性/逻辑删 | ✅ | ProductSpu.java:27-45；sql/product/V2__product.sql:55-61；SpuServiceImpl.java:279-281 | 无独立详情图字段，仅 detail_json 富文本 |
| F-PROD-2 | SKU：编码/规格组合/三价/库存/重量体积/条码 | ✅ | ProductSku.java:28-67,70；SkuSaveRequest.java:21-47 | — |
| F-PROD-3 | 三级类目/类目属性/虚拟类目（挂多个二级） | ⚠️ | ProductCategory.java:18-21；CategoryServiceImpl.java:64-70,122-146；V2__product.sql:21 | 属性仅 attr_template_json 不按模板校验；虚拟类目完全缺失（单字段 category3_id） |
| F-PROD-4 | 品牌管理 | ✅ | ProductBrand.java:14-30；BrandServiceImpl.java:29-81；BrandController.java:31-51 | — |
| F-PROD-5 | 规格名/规格值主数据管理 | ⚠️ | SkuSaveRequest.java:31-32 specText；SpuServiceImpl.java:185 | 仅自由文本 specText/attrsJson，无规格主数据表与管理接口 |
| F-PROD-6 | 商品八态状态机+可见/可下单控制 | ✅ | GoodsStatuses.java:11-25；GoodsStateMachine.java:34-69；SpuServiceImpl.java:54-55,291-303；StockServiceImpl.java:240-241 | — |
| F-PROD-7 | 上架审核流（商家提交/平台审批） | ✅ | MerchantGoodsController.java:55-58；SpuServiceImpl.java:209-232,255-264；AdminGoodsController.java:44-48 | — |
| F-PROD-8 | 库存四类型：可售/预售/锁定/占用 | ⚠️ | ProductSku.java:70-88；StockTypes.java:11-17；V2__product.sql:104-107 | 第四栏实为"残次仓"非预售；预售仅 stock_type 标记，无预售库存数量 |
| F-PROD-9 | 库存锁定/支付确认/取消回补/发货扣减 | ⚠️ | StockServiceImpl.java:108,365-420；ProductSkuMapper.java:19-38,27-30；AftersaleStockListener+StockServiceImpl.java:424-455 | 无发货事件监听，支付后停在 occupied，"发货扣减"未独立实现 |
| F-PROD-10 | 防超卖 | ✅ | ProductSkuMapper.java:19-22（WHERE available>=qty）；StockServiceImpl.java:67,102-112；@Version ProductSku.java:94-95；流水 UK | 无 Redis 预占（DB 条件更新+Redisson 锁方案） |
| F-PROD-11 | 库存预警(默认10)/0 自动下架/补货自动上架 | ✅ | StockServiceImpl.java:276-309,318-338；V2__product.sql:108；MerchantGoodsController replenish | 预警记录无查询端点、无去重 |
| F-PROD-12 | 五价体系取最低/秒杀促销不叠加 | ⚠️ | ProductSku.java:52-64；PriceServiceImpl.java:76-96 | 商品域不做互斥拦截（注释明确归营销域） |
| F-PROD-13 | 评价：15天/三维星/字数/图9/视频1×30s/追评180天/回复1次/好评率 | ✅ | CommentServiceImpl.java:44-46,72-76,109-146,182-211；CommentCreateRequest.java:33-66 | 完成时间无专用字段，以 updateTime 兜底 |
| F-PROD-14 | 敏感词过滤/人工抽检 | ⚠️ | SensitiveWordServiceImpl.java:21-41 | 词库硬编码 17 词、contains 匹配、仅评价场景；人工抽检缺失 |
| F-PROD-15 | 详情缓存/C 端浏览搜索/商家管理接口 | ⚠️ | SpuDetailCache.java:24-71；GoodsController.java:32-58；SpuServiceImpl.java:357-374 | 缓存无 TTL、无活动变价失效；搜索为 name LIKE 无 ES |
| F-PROD-16 | 预售库存支付后扣减 | ❌ | 仅 ProductSku.java:85-88 presaleFlag/stockType；StockServiceImpl.java:464-473 | handleOrderCreated 对所有类型一律下单即锁，预售语义未实现 |

## 三、营销模块（13 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-MKT-1 | 满减多级/满折/满赠/第N件/限时折扣 | ⚠️ | PromoAdminService.java:32-90；PriceEngine.java:141-152,200-248,254-283,490-570 | 五类均有真实计算；满赠赠品不锁库存/不出库；限时折扣门槛阈值被忽略 |
| F-MKT-2 | 优惠券 6 类型核销 | ⚠️ | CouponTypes.java:12-22；PriceEngine.java:330-363,380-400 | 品类券/店铺券无按 type 分支，靠调用方所放槽位，券类型↔槽位一致性不校验（可错槽） |
| F-MKT-3 | 券模板创建+平台审核 | ❌ | CouponAdminService.java:29-85；Coupon.java:39（仅 0下架/1上架/2作废） | 无待审核/通过/驳回状态，marketing 模块 grep 审核零命中 |
| F-MKT-4 | 领券中心/每人限1/五种发放方式 | ⚠️ | CouponCenterController.java:30-43；CouponService.java:126-138,180-187；InnerMarketingController.java:58-63 | 仅主动领取真正接线；活动/新人/补偿/积分兑换无生产调用方 |
| F-MKT-5 | 券生命周期：锁/核销/退回/过期/作废 | ⚠️ | UserCouponMapper.java:15-32；MarketingTxOps.java:43-107；CouponExpireJob.java:18-24 | 状态3"已作废"无任何写入点；模板作废不连带已发券，无撤回/删除接口 |
| F-MKT-6 | 六层叠加/同层1张/满减满折互斥/秒杀互斥/分摊守恒 | ✅ | PriceEngine.java:59-120,200-218,303-308,405-419,444-471,590-609 | 分摊权重用扣减后金额而非原价；SKU 行三类券合并为一个 couponAlloc |
| F-MKT-7 | 秒杀：独立库存/预占/限购/审核/状态机/下单链路 | ⚠️ | SeckillStockClient.java:39-155；SeckillService.java:61-203,212-220；MarketingTxOps.java:54-83 | 无活动审核；限购固化"每活动1单"无可配 N 件；SECKILL_EVENT 无消费者；无到点自动结束；seckill_price 不被读取 |
| F-MKT-8 | 秒杀对账（预占回补/Redis-DB 对账） | ⚠️ | SeckillReconcileJob.java:27-42 | 不一致仅 log.warn 不修正；无 status=0 预占单扫描回补（仅依赖订单取消事件） |
| F-MKT-9 | 拼团：人数档/24h/每人1次/失败退款/成功待发货/团长优惠 | ⚠️ | GroupbuyService.java:54-105,141-172,184-191；GroupbuyExpireJob.java:18-25；PriceEngine.java:74-85 | GROUPBUY_EVENT 全仓无消费者：失败自动退款、成功转待发货均未落地；团长优惠仅字段无计价 |
| F-MKT-10 | 预售：定金+尾款/膨胀/尾款3天/未付不退/尾款用券 | ⚠️ | PresaleService.java:35,56-107；PresaleFinalJob.java:18-24；PresaleEventListener.java:38-44；PriceEngine.java:78-80 | 定金膨胀 inflateDeductFen 只登记不参与计价 |
| F-MKT-11 | 砍价/抽奖（含积分抽奖） | ❌ | 仅 BargainRecord.java、LotteryRecord.java 空实体+空 BaseMapper；ActivityRule.java:26-32 配置字段 | 无 service/controller/任何业务方法 |
| F-MKT-12 | 促销/券/活动平台后台审核流 | ❌ | PromoAdminController.java:31-42；CouponAdminController.java:30-41；ActivityAdminController.java:27-38 | 三类均为平台管理员 save+changeStatus 直建直上架，无提交→审批角色分离 |
| F-MKT-13 | 优惠分摊透传订单/退款 | ✅ | CalcLine.java:20-24；ItemPriceDetail.java:31-50；OrderCreateServiceImpl.java:390-394；AftersaleServiceImpl.java:887,904 | SKU 级不区分三类券 |

## 四、订单模块（16 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-ORD-1 | 订单+明细全字段（金额构成/支付/发票/来源/类型/备注/分摊） | ✅ | Order.java:21-88；OrderItem.java:17-51；OrderInvoice.java:18-35 | OrderDTO 不回传物流字段/refundedFen |
| F-ORD-2 | 18 位订单号规则（01-05 业务码） | ✅ | OrderNoGenerator.java:50-82；OrderTypes.java:35-47 | 日序列 >999999 退化雪花，不保证 18 位 |
| F-ORD-3 | 九态订单状态机 | ⚠️ | OrderStateMachine.java:25-62；OrderStatuses.java:16-40 | 无"支付失败"订单态（支付失败停留待付款直到超时）；MQ 路径走 SQL 条件更新不经状态机 |
| F-ORD-4 | 下单 8 项前置校验 | ✅ | OrderCreateServiceImpl.java:89-92,164-278；PurchaseLimitChecker.java:38-45；RegionDeliveryChecker.java:15-30 | 区域校验仅地址归属（默认全国可配，无禁配规则）；积分不足靠 lockPoints 后置拒绝 |
| F-ORD-5 | 下单三步（锁库存/券预核/积分预扣/落单）+幂等+补偿 | ✅ | OrderCreateServiceImpl.java:87,106-147,475-514；OrderPersister.java:49-72 | 幂等仅 Redis 切面，DB 无 clientToken 唯一键兜底 |
| F-ORD-6 | 价格试算/运费计算/地区运费 | ⚠️ | MobileMarketingController.java:23-25；PriceEngine.java:109-111,421-435；CreateOrderRequest.java:40 | 无运费模板/区域运费规则，freightFen 由客户端上送默认 0（可被篡改） |
| F-ORD-7 | 支付成功扇出（状态/库存/券/积分/清算/通知） | ✅ | PayEventConsumer.java:44-61；OrderPaidStockListener.java:36-38；marketing OrderPaidListener.java:35-40；PaymentSucceededListener.java:35-36 | 无短信/站内信/Push 用户触达；提醒发货仅置标记 |
| F-ORD-8 | 超时矩阵：普通30分/秒杀15分/拼团24h+30分/预售3天+释放资源 | ⚠️ | PayTimeoutPolicy.java:31-37；PayTimeoutScanJob.java:32-51；OrderResourceReleaser.java:33-69 | 拼团"成团后30分钟续期"无代码；成团事件无消费者（见 F-MKT-9） |
| F-ORD-9 | 发货 10 天自动收货/15 天售后窗口 | ✅ | OrderTimePolicy.java:20-27；AutoConfirmScanJob.java:32-51；AftersaleWindowScanJob.java:32-51；OrderOperateServiceImpl.java:207-230 | — |
| F-ORD-10 | 购物车：店铺分组/99 上限/失效/勾选/改量/移收藏/清失效 | ✅ | CartServiceImpl.java:43-45,54-300；CartController.java:33-92 | 移入收藏不删购物车行（注释明示，属产品取舍） |
| F-ORD-11 | 收藏夹 | ⚠️ | Favorite.java:16-28；CartServiceImpl.java:121-140 | 仅"购物车移入"一条路径，无独立收藏/取消/列表接口 |
| F-ORD-12 | 取消/删除/再来一单/改地址/提醒发货/查看物流 | ⚠️ | OrderOperateServiceImpl.java:69-294；OrderController.java:63-67 | 查看物流无接口且 DTO 不回传物流单号/公司，无轨迹查询 |
| F-ORD-13 | 商家发货/订单筛选/用户列表详情 | ✅ | ShipRequest.java:14-18；OrderOperateServiceImpl.java:122-150；MerchantOrderController.java:35-46；OrderQueryServiceImpl.java:33-51 | — |
| F-ORD-14 | 发票三类型/抬头税号/完成后开具/PDF 邮箱/退款冲红 | ⚠️ | InvoiceServiceImpl.java:100-149；InvoiceIssueJob.java:24-31；PayEventConsumer.java:82-86；OrderInvoice.java:21-35 | PDF 为伪地址、邮箱仅 log，无真实开票与邮件投递 |
| F-ORD-15 | 售后状态联动（退款/退货/换货映射/关闭） | ✅ | AftersaleStatusMapping.java:31-68；AftersaleEventConsumer.java:36-75 | — |
| F-ORD-16 | 秒杀/拼团/预售/换货差异化下单 | ⚠️ | CreateOrderRequest.java:24,52-61；OrderCreateServiceImpl.java:520-544；MarketingTxOps.java:53-60 | 活动 ID 可空时活动锁被静默跳过（可普通价买活动品）；换货单(type5)无创建入口 |

## 五、支付模块（13 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-PAY-1 | 7 种支付方式 | ⚠️ | PayMethods.java:9-27（全）；ChannelRouter.java:21-29；MockPayChannelClient.java:36-38 | 6 个在线渠道仅有 mock 适配器，无真实 SDK |
| F-PAY-2 | 终端矩阵/单笔限额 | ✅ | ChannelLimits.java:16-43,72-84；Terminals.java:8-17 | terminal 为空时跳过终端校验 |
| F-PAY-3 | 创建支付单+调渠道（组合支付 parts） | ⚠️ | PayController.java:32,105-122；PaymentServiceImpl.java:233-298；PayCreateRequest.java:27-30 | 渠道侧 mock（cashier URL 拼装） |
| F-PAY-4 | 余额支付内部扣减 | ✅ | PaymentServiceImpl.java:164-183,309-352；UserClient.debitBalance；InnerUserController.java:108-111 | — |
| F-PAY-5 | 回调验签/幂等/金额核对/扇出 | ✅ | SignVerifier.java:34-50,95-97；PaymentServiceImpl.java:371-491,635-646；NotifyLog UK | 无支付成功用户通知 |
| F-PAY-6 | 支付 7 态状态机 | ✅ | PayStatuses.java:9-27；PaymentStateMachine.java:30-40 | markFail/markClosed 绕过状态机（SQL 状态约束兜底） |
| F-PAY-7 | 支付超时扫描（扫描+延时双保险） | ✅ | PayTimeoutJob.java:24-35；PayCheckDelayListener.java:47-52；PaymentServiceImpl.java:551-573 | — |
| F-PAY-8 | 主动查单补偿 | ⚠️ | PaymentServiceImpl.java:497-548；ChannelQueryResult.java:16 | mock 查询恒返回 SUCCESS |
| F-PAY-9 | 原路退/余额退/混合按比例拆/部分退款累计不超实付 | ✅ | RefundSplitter.java:21-36；RefundServiceImpl.java:195-262,285-300；PaymentMapper.java:74-77 | 券不退/积分比例退在售后域实现 |
| F-PAY-10 | 退款单状态机/退款异步回调/三方退款流水 | ⚠️ | RefundStateMachine.java:25-39（无引用，死代码）；RefundSplit.channelRefundNo:24；MockPayChannelClient.refund:67-72 | 无退款回调端点（仅 /notify/pay），mock 退款同步成功，无异步查询补偿 |
| F-PAY-11 | T+1 对账：账单下载/三类差异/自动处理/差错工单 | ⚠️ | ReconcileMatcher.java:35-79；ReconcileServiceImpl.java:209-272；ReconcileController.java:41-52；ReconcileDiffTypes.java:8-15 | 账单读 classpath mock 文件且无文件/上传；短款只计次不真查单、不自动关单；无差错工单实体 |
| F-PAY-12 | 对账批次幂等/重试 Job | ✅ | ReconBatch.java:12；ReconcileServiceImpl.java:66-72；ReconcileJob.java:28-39；ReconRetryJob.java:23-34 | 失败批次重跑靠手动 POST |
| F-PAY-13 | 渠道路由/密钥配置化/prod fail-fast | ⚠️ | ChannelRouter.java:15-29；ChannelSecretProvider.java:52-64,126-142 | 容器仅 mock 一个 bean，关闭 mock 后无真实适配器可用 |

## 六、结算模块（13 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-SET-1 | 平台/商户/用户/营销四类账户 | ⚠️ | SettAccount.java:19-20；AccountRole.java:18-27；SettleClearingExecutor.java:92-102；MerchantService.java:59 | 用户余额账户(role=3)结算侧零对接；保证金为商户表字段非账户 |
| F-SET-2 | 佣金5-15%按类目/技服费0.5元或0.1%/通道费0.6% | ⚠️ | SplitEngine.java:26-29,61-68；ClearingService.java:91,150 | 佣金取商户录入费率无类目费率表；技服费仅 0.5 固定档；技服费计平台收入却未从商户应收扣（每单 50 分资金守恒缺口，SplitEngine.java:68 vs SettleClearingExecutor.java:95-97） |
| F-SET-3 | 分账公式+优惠承担方（店铺券商户/平台券积分平台） | ⚠️ | SplitEngine.java:59-72；ClearingService.java:89-90,148-149 | 满减跨方分摊无独立字段（并入两桶）；技服费未扣商户 |
| F-SET-4 | 三清算节点：支付登记/收货待结算/售后期满可提现 | ✅ | PaymentSucceededListener.java:35,71-114；OrderConfirmedListener.java:35,120-165；OrderCompletedListener.java:35,182-207；DailySettleJob.java:23 | — |
| F-SET-5 | S/A/B/C → T+1/7/15/30 结算周期 | ✅ | SettleCycle.java:24-48；SettMerchant.java:22；UpdateLevelRequest.java:13-15 | 到账时效 T+0/T+1/T+3 分级未做（统一 T+1） |
| F-SET-6 | 商户入驻/等级调整/清退 | ⚠️ | AdminController.java:38-63；MerchantService.java:30-73,91-93 | 无禁用/恢复接口（DISABLED 只读不写） |
| F-SET-7 | 提现：100 起/日50万/前3笔免费后0.1%最低2元/银行卡支付宝 | ✅ | WithdrawCalculator.java:19-67；ApplyWithdrawRequest.java:14；WithdrawChannels.java:7-8；WithdrawDailyCountMapper.java:19-36 | 未按等级区分 T+0/T+3 |
| F-SET-8 | 提现审核流/三方打款流水/手续费 | ⚠️ | WithdrawAuditJob.java:20,147-162；WithdrawRemitJob.java:22,169；SettWithdraw.feeFen；WithdrawService.java:191-194 | 风控审核为"模拟自动过审"，refuse/markFailed 无接口暴露；打款 mock 无渠道流水号字段 |
| F-SET-9 | 自动提现（每日/每周） | ✅ | SettWithdrawAutoConfig.java:19-29；AutoWithdrawJob.java:22,313-351；MerchantWithdrawController.java:58-69 | — |
| F-SET-10 | 保证金：1000-50000/赔付扣减/低于50%限提/清退90天退还 | ⚠️ | MerchantService.java:39-42；DepositService.java:79-136,187-199；DepositRefundJob.java:22,154-175；WithdrawService.java:112-115 | 缴费 mock 无支付链路；罚款类型 FINE 空置无接口；90 天"无纠纷"无数据源；退还只清零不打款 |
| F-SET-11 | 退款清算瀑布（待结算→保证金）/佣金补贴退回/通道费不退 | ✅ | ClearingReverseService.java:64-216；RefundCalculator.java:45-70；RefundShortfallEvent.java:26-43；RefundSucceededListener.java:36 | 三档扣尽后缺口仅发事件挂起，追讨未实现 |
| F-SET-12 | 账户流水/商户对账单 | ⚠️ | SettAccountFlow.java:16-28；FlowChangeTypes.java:10-47；AccountService.java:54-58；SettStatement.java:25-28 | 账户流水无查询 API；结算单无佣金/费用明细行 |
| F-SET-13 | 银行卡号等 AES-GCM 加密+脱敏 | ✅ | DataCipher.java:37-105；WithdrawService.java:130-132,296-297；AccountMask.java:16-38；V3__data_encryption.sql | bankName 明文 |

## 七、售后模块（15 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-AS-1 | 五类售后（仅退/退货退款/换货/补发/价保） | ✅ | AftersaleTypes.java:24-36；AftersaleServiceImpl.java:181,336-446,617-620 | 补发/价保无审核超时自动流转（autoApprove 仅 1/2/3 型，:695-698） |
| F-AS-2 | 仅退款全流程（含拒绝后修改/介入/撤销） | ✅ | AftersaleServiceImpl.java:209-353,262-306,468-503；AftersaleMqServiceImpl.java:175-193 | — |
| F-AS-3 | 退货退款流程（填物流/商家收货/拒收） | ✅ | AftersaleServiceImpl.java:334-404,359-391 | — |
| F-AS-4 | 换货流程+发货 5 天超时转退款 | ✅ | AftersaleServiceImpl.java:405-462,714-729；AftersaleCodes.java:57 | 转退款金额取申请值（换货为0时回退 paidFen），未走 RefundCalculator 重算运费/积分 |
| F-AS-5 | 售后状态机+并发守护+状态日志 | ✅ | AftersaleStateMachine.java:29-75；AftersaleServiceImpl.java:817-839；StatusLog.java:13-21 | 状态机个别边（80/30→90）无接口对应 |
| F-AS-6 | 申请时限（发货前/收货前/完成15天/质保30天90天1年） | ⚠️ | AftersalePolicy.java:89-116；AftersaleMqServiceImpl.java:111-112；AftersaleServiceImpl.java:890,936-938 | 无类目质保数据源，质保期统一硬编码 15 天 |
| F-AS-7 | 多次售后/累计退款≤实付/明细单一进行中 | ✅ | OrderItemRefMapper.java:16-19；AftersaleServiceImpl.java:165-173,869-873；OrderItemRefundMapper:25-31 | — |
| F-AS-8 | 退款金额：实付-已退/券不退/积分比例退/运费责任承担 | ✅ | RefundCalculator.java:34-81；AftersaleServiceImpl.java:172-193 | 运费险责任方返回 0（理赔另走 F-AS-12） |
| F-AS-9 | 商家超时：2/2/2 天自动同意、3 天自动收货、5 天转退款 | ⚠️ | AftersaleServiceImpl.java:250-253,373,409,695-729；AftersaleTimeoutJob.java:29-62；AftersaleCodes.java:55-57 | 退货自动同意无退货地址下发（全仓无 returnAddress）；补发/价保无超时 |
| F-AS-10 | 平台介入：3 天举证/5 工作日仲裁/终局执行 | ⚠️ | AftersaleServiceImpl.java:473-581；AftersaleClaimJob.java:43-52；AftersalePolicy.java:58-73；PlatformAftersaleController.java:29 | 5 工作日只记 deadline，无超时提醒/自动裁决；仲裁不校验超期 |
| F-AS-11 | 价保：7天/大促30天/排除秒杀拼团/每单一次 | ⚠️ | AftersalePolicy.java:21,131-159；AftersaleCodes.java:61-62；AftersaleServiceImpl.java:125-128,617-620,617 | 价保不退积分（pointsRefund 恒 0）；"每单一次"收窄为单明细一次 |
| F-AS-12 | 运费险：0.5-5 元购买/72h 理赔/封顶25/每单一次 | ⚠️ | AftersaleInsurance.java:16-26；AftersaleClaimJob.java:31-41；AftersaleTimeoutServiceImpl.java:55-79；AftersaleMqServiceImpl.java:205-242 | 下单购买侧完全缺失，hasFreightInsurance 恒 0、保费无人收取，理赔链路生产不可达；claimFen 恒 25 无实退运费 |
| F-AS-13 | 库存联动（质量入残次/非质量回可售/换货） | ✅ | AftersaleServiceImpl.java:398-402,848-861；AftersaleStockListener:36-38；StockServiceImpl.java:424-455 | 买家责任存在 Feign 即时+MQ 两条回库路径，依赖 product 侧去重 |
| F-AS-14 | 联动支付退款/退积分/结算冲正/订单售后态 | ✅ | AftersaleServiceImpl.java:741-812；AftersaleMqServiceImpl.java:152-193；结算 ClearingReverseService（RefundSucceededListener.java:36）；AftersaleEventConsumer.java:33-75 | — |
| F-AS-15 | 售后单号/用户商家平台三端接口 | ⚠️ | AftersaleNoGenerator.java:30-41；AftersaleController.java:37-102；MerchantAftersaleController.java:34-69；PlatformAftersaleController.java:25-32 | 无"催促商家处理"接口；平台端仅仲裁无分页/详情/举证查看；商家端无详情 |

## 八、网关（4 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-GW-1 | 七服务路由转发+Nacos 发现 | ✅ | shop-gateway/src/main/resources/application.yml:19-61 | — |
| F-GW-2 | JWT 全局鉴权/白名单/身份头清洗/防路径绕过 | ⚠️ | JwtAuthGlobalFilter.java:49-64,80-132,140-188 | 网关不做 user_type 角色授权（utype 仅透传），授权下沉各服务 |
| F-GW-3 | 网关限流 | ❌ | gateway pom 无 redis 依赖、yml 无限流配置 | 网关层无 RateLimiter；服务层有 @RateLimit（RateLimitAspect.java:56-79，已落登录/领券/秒杀/下单/提现 5 处）部分缓解 |
| F-GW-4 | 内部 token 鉴权/密钥 fail-fast | ✅ | InternalTokenInterceptor.java:28-37；FeignRequestInterceptor.java:22-26；GatewaySecretValidator.java:31-42 | — |

## 九、基础设施（5 项）

| 编号 | 功能点 | 状态 | 证据（类:行） | 缺项说明 |
|---|---|---|---|---|
| F-INF-1 | 分布式锁/幂等切面 | ✅ | DistributedLockTemplate.java:44-62（Redisson 看门狗）；IdempotentAspect.java:119-154（Redis SET NX） | 幂等无 DB 兜底 |
| F-INF-2 | RocketMQ 封装/消费幂等/重试死信/Outbox/延时 | ✅ | MqProducer.java:73-151；MqErrorPolicy.java:29-49；MqConsumerRegistrar.java:171-176；OutboxPublisher.java:36-58；OutboxRelayJob.java:55-87 | — |
| F-INF-3 | 定时任务选主 ShedLock | ✅ | ShedLockConfig.java:13-20；各 Job @SchedulerLock | — |
| F-INF-4 | 统一异常/返回/JWT/MyBatis/脱敏/Long 分金额/全链路日志 | ⚠️ | GlobalExceptionHandler.java:29-67；JwtService.java:30-63；MybatisPlusConfig.java:19-43；MoneyUtils.java:44-76 | 无全链路 traceId（无 MDC/Filter，logback pattern 无 traceId，Feign 不透传） |
| F-INF-5 | 监控（actuator/prometheus/grafana/健康检查） | ⚠️ | shop-framework/pom.xml:33-37；deploy/prometheus/prometheus.yml；grafana dashboards；MqConsumerHealthIndicator.java:24-43 | 网关 pom 缺 micrometer-registry-prometheus（/prometheus 实际无指标）；无业务自定义指标 |

---

## 十、结论

### 10.1 完备率统计（共 107 个功能点）

| 模块 | 总数 | ✅已实现 | ⚠️部分 | ❌缺失 | 严格完备率 |
|---|---|---|---|---|---|
| 用户 | 12 | 5 | 6 | 1 | 42% |
| 商品 | 16 | 8 | 7 | 1 | 50% |
| 营销 | 13 | 2 | 8 | 3 | 15% |
| 订单 | 16 | 8 | 8 | 0 | 50% |
| 支付 | 13 | 7 | 6 | 0 | 54% |
| 结算 | 13 | 6 | 7 | 0 | 46% |
| 售后 | 15 | 9 | 6 | 0 | 60% |
| 网关 | 4 | 2 | 1 | 1 | 50% |
| 基础设施 | 5 | 3 | 2 | 0 | 60% |
| **合计** | **107** | **50** | **51** | **6** | **46.7%** |

- 严格完备率（仅计 ✅）：**50/107 = 46.7%**
- 有代码覆盖率（✅+⚠️）：**101/107 = 94.4%**；完全无代码的功能点仅 6 项（F-USER-11、F-PROD-16、F-MKT-3、F-MKT-11、F-MKT-12、F-GW-3）
- 总体判断：交易主干（商品浏览→购物车→下单 TCC→支付扇出→状态机→超时→收货→清算→售后退款）链路可跑通且有 E2E 覆盖；缺口集中在**营销活动玩法闭环、平台审核流、真实三方对接、资金/通知边角**。

### 10.2 阻断性缺口清单（影响 design 承诺能力不可用或资金/越权风险）

| # | 缺口 | 涉及编号 | 后果 |
|---|---|---|---|
| B1 | 拼团事件 GROUPBUY_EVENT 无任何消费者：失败自动退款、成功转待发货、团长优惠均未落地；成团后 30 分钟支付续期无代码 | F-MKT-9、F-ORD-8 | 拼团玩法为半成品，成团后订单无后续流转 |
| B2 | 营销三类后台（促销/券/秒杀活动）全部无"提交→平台审核"流，平台管理员直建直上架；秒杀无活动审核、无到点自动结束 | F-MKT-3、F-MKT-7、F-MKT-12 | design 要求的平台审核能力缺失 |
| B3 | 砍价、抽奖（含积分抽奖）仅空实体/Mapper，无任何业务代码 | F-MKT-11 | 两类活动完全不可用 |
| B4 | 秒杀 SECKILL_EVENT 无消费者；活动 ID 可空时营销锁被静默跳过（可按普通价/普通库存下成秒杀单）；秒杀对账只告警不修正、无预占回补扫描；限购固化 1 单 | F-MKT-7、F-MKT-8、F-ORD-16 | 秒杀正确性与对账兜底不足 |
| B5 | 预售：定金膨胀只登记不计价；商品侧预售库存仍为下单即锁而非支付后扣减 | F-MKT-10、F-PROD-16 | 预售资金与库存语义不符合设计 |
| B6 | 新人礼包未接线；评价/晒单/分享的成长值与积分无任何触发方（跨域事件缺失） | F-USER-3、F-USER-5、F-USER-11 | 用户成长/激励体系大半不运转 |
| B7 | 无运费模板/区域运费规则，运费由客户端上送（默认 0） | F-ORD-6 | 运费金额可被客户端篡改，影响实付与分账 |
| B8 | 全部在线支付渠道、退款、提现打款、对账单均为 mock；退款无异步回调端点，RefundStateMachine 为死代码，无退款查询补偿 | F-PAY-1/3/8/10/11/13、F-SET-8 | 上线前必须接真实渠道（架构已留 PayChannelClient SPI 且 prod 禁 mock） |
| B9 | 资金正确性：技服费 0.5 元计入平台收入却未从商户应收扣减（资金不守恒，SplitEngine.java:68 vs SettleClearingExecutor.java:95-97） | F-SET-2、F-SET-3 | 每单 50 分平台虚记收入 |
| B10 | 保证金缴费/清退退还无真实资金链路（mock 缴费、退还只置零不打款），罚款类型空置，90 天"无纠纷"无数据源 | F-SET-10 | 保证金机制名义存在、资金不闭环 |
| B11 | 运费险下单购买侧缺失（hasFreightInsurance 恒 0、不收保费），理赔虽实现但生产不可达 | F-AS-12 | 运费险整体不可用 |
| B12 | 网关无限流（服务层 @RateLimit 仅覆盖 5 个热点接口，未覆盖全部门面） | F-GW-3 | 大流量/恶意访问在网关层无第一道防线 |
| B13 | 商品虚拟类目（多二级挂载）、规格主数据管理缺失；库存无"发货扣减"节点（占用仓不出账） | F-PROD-3、F-PROD-5、F-PROD-9 | 类目/履约模型不完整 |

### 10.3 非阻断取舍 / 残留项（不影响主干，建议排期）

- 用户：游客仅枚举无会话；无 C 端余额充值入口、赠金无入账；地址变更不验手机号；无补签；生日礼包无字段支撑（User 无 birthday）。
- 商品：详情图并入 detail_json；属性模板不校验；预警无查询/去重；敏感词硬编码 17 词无人工抽检；缓存无 TTL/活动失效；搜索 LIKE 无 ES。
- 营销：满赠赠品不锁库存；品类/店铺券靠槽位不校验类型一致性；券"已作废"无写入点、模板作废不连带用户券；分摊用折后价权重、SKU 不拆三类券。
- 订单：无支付失败态；无物流轨迹接口（单号已存）；发票 PDF/邮件为模拟；订单号极端流量退雪花；幂等无 DB 唯一键；收藏夹仅移入路径；换货单无创建入口；无任何用户消息触达（短信/Push/站内信全缺）。
- 支付：状态机失败/关闭分支走 SQL；对账无差错工单实体、短款不真查单、批次失败仅手动重跑。
- 结算：佣金按商户录入非类目费率；到账统一 T+1 无 T+0/T+3 分级；提现风控自动过审、人工接口未暴露；账户流水无查询 API、结算单无费用明细；结算侧无用户余额账户对接。
- 售后：质保期统一 15 天；退货自动同意不下发退货地址；仲裁无超时自动裁决；价保不退积分/仅单明细；无催单接口、平台端接口偏少；换货转退款金额未重算。
- 网关/基建：无路径级角色授权（各服务自兜底）；无全链路 traceId；网关 prometheus registry 缺失；无业务自定义指标。
- 非功能配套：deploy/loadtest 有 5000 TPS k6 脚本、K8s 双副本/HPA/PDB、Prometheus/Grafana 已具备，但 design 10.1 性能指标无实测报告存档；防超卖/防重提交已落地，"防刷单"靠限流+登录锁定，无独立风控系统。
