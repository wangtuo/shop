package com.shop.marketing.activity.lottery.controller;

import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.marketing.activity.entity.LotteryRecord;
import com.shop.marketing.activity.lottery.dto.DrawRequest;
import com.shop.marketing.activity.lottery.dto.DrawResult;
import com.shop.marketing.activity.lottery.dto.PrizeStockVO;
import com.shop.marketing.activity.lottery.service.LotteryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** C 端积分抽奖：发起抽奖 / 奖品余量 / 我的奖品（登录态走既有拦截器）。 */
@RestController
@RequestMapping("/h5/lottery")
@RequiredArgsConstructor
@Validated
public class LotteryController {

    private final LotteryService lotteryService;

    /** 积分抽奖一次（积分 lock→deduct 原子两阶段，日限由 Redis + DB count 双控）。 */
    @PostMapping("/draw")
    public Result<DrawResult> draw(@Valid @RequestBody DrawRequest req) {
        return Result.success(lotteryService.draw(UserContext.getUserId(), req.getActivityId()));
    }

    /** 奖品与剩余库存（谢谢参与/不限量不返回余量）。 */
    @GetMapping("/prizes")
    public Result<List<PrizeStockVO>> prizes(
            @RequestParam("activityId") @Positive(message = "活动ID必须为正数") Long activityId) {
        return Result.success(lotteryService.prizes(activityId));
    }

    /** 我的抽奖记录/奖品；activityId 可选，缺省查全部活动。 */
    @GetMapping("/my")
    public Result<List<LotteryRecord>> my(
            @RequestParam(value = "activityId", required = false) Long activityId) {
        return Result.success(lotteryService.myPrizes(UserContext.getUserId(), activityId));
    }
}
