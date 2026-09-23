package com.shop.api.user.client;

import com.shop.api.user.dto.AddressDTO;
import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.api.user.dto.UserDTO;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户域内部 Feign 契约（shop-user-service，路径前缀 /inner/user）。
 *
 * <p>仅供其他业务服务同步调用；跨域状态变更的最终一致以 MQ 事件为准。
 * 所有写操作以命令中的 bizNo 作为幂等键，服务端必须保证重复调用不产生副作用。
 *
 * <p>规则来源：CONTRACTS.md §3 跨域同步契约、§5 关键链路；design.md 第二章 用户模块。
 */
@FeignClient(name = "shop-user-service", path = "/inner/user")
public interface UserClient {

    /**
     * 查询用户基础信息。
     *
     * @param userId 用户 ID
     * @return 用户基础信息（用户不存在返回业务错误码）
     */
    @GetMapping("/get")
    Result<UserDTO> getUser(@RequestParam("userId") Long userId);

    /**
     * 查询用户会员等级及权益（折扣、积分倍率）。
     *
     * @param userId 用户 ID
     * @return 会员等级信息
     */
    @GetMapping("/level")
    Result<UserLevelDTO> getLevel(@RequestParam("userId") Long userId);

    /**
     * 查询收货地址（下单时校验并获取收货信息）。
     *
     * @param addressId 地址 ID
     * @return 收货地址
     */
    @GetMapping("/address")
    Result<AddressDTO> getAddress(@RequestParam("addressId") Long addressId);

    /**
     * 下单预扣积分：可用积分 → 冻结（TCC-try）。
     *
     * @param cmd 冻结命令（含积分个数与抵现金额，单位分）
     * @return 空结果
     */
    @PostMapping("/points/lock")
    Result<Void> lockPoints(@Valid @RequestBody PointsLockCommand cmd);

    /**
     * 支付成功：冻结积分正式扣除（TCC-confirm）。
     *
     * @param cmd 实扣命令（userId + bizNo）
     * @return 空结果
     */
    @PostMapping("/points/deduct")
    Result<Void> deductPoints(@Valid @RequestBody PointsDeductCommand cmd);

    /**
     * 订单取消/超时未支付：释放冻结积分回可用余额（TCC-cancel）。
     *
     * @param cmd 释放命令（userId + bizNo）
     * @return 空结果
     */
    @PostMapping("/points/release")
    Result<Void> releasePoints(@Valid @RequestBody PointsReleaseCommand cmd);

    /**
     * 退款成功：按退款比例退回积分。
     *
     * @param cmd 退回命令（userId + bizNo + 按比例计算的积分个数）
     * @return 空结果
     */
    @PostMapping("/points/refund")
    Result<Void> refundPoints(@Valid @RequestBody PointsRefundCommand cmd);

    /**
     * 发放积分：消费返积分、签到、评价、分享、晒单、补偿等。
     *
     * @param cmd 发放命令
     * @return 空结果
     */
    @PostMapping("/points/grant")
    Result<Void> grantPoints(@Valid @RequestBody GrantPointsCommand cmd);

    /**
     * 增加成长值：消费 1 元 = 1、评价 +10、晒单 +20、连续签到 7 天 +50。
     *
     * @param cmd 成长值命令
     * @return 空结果
     */
    @PostMapping("/growth/add")
    Result<Void> addGrowth(@Valid @RequestBody GrowthCommand cmd);

    /**
     * 余额支付扣款（从余额账户扣减实付金额，单位分）。
     *
     * @param cmd 金额变动命令
     * @return 空结果
     */
    @PostMapping("/balance/debit")
    Result<Void> debitBalance(@Valid @RequestBody AmountCommand cmd);

    /**
     * 退款入余额（余额支付原路退回 / 退款实时到账，单位分）。
     *
     * @param cmd 金额变动命令
     * @return 空结果
     */
    @PostMapping("/balance/credit")
    Result<Void> creditBalance(@Valid @RequestBody AmountCommand cmd);

    /**
     * 赠金扣款（活动赠金/补偿资金消费扣减，单位分）。
     *
     * @param cmd 金额变动命令
     * @return 空结果
     */
    @PostMapping("/gift/debit")
    Result<Void> debitGift(@Valid @RequestBody AmountCommand cmd);
}
