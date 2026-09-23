package com.shop.marketing.activity.bargain.controller;

import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.marketing.activity.bargain.dto.BargainDetailVO;
import com.shop.marketing.activity.bargain.dto.HelpCutVO;
import com.shop.marketing.activity.bargain.dto.StartBargainRequest;
import com.shop.marketing.activity.bargain.service.BargainService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端砍价：发起 / 帮砍 / 详情（登录态经既有拦截器写入 UserContext）。 */
@RestController
@RequestMapping("/h5/bargain")
@RequiredArgsConstructor
@Validated
public class BargainController {

    private final BargainService bargainService;

    /** 发起砍价（同用户同活动重复发起幂等返回进行中记录 ID）。 */
    @PostMapping("/start")
    public Result<Long> start(@Valid @RequestBody StartBargainRequest req) {
        return Result.success(bargainService.startBargain(UserContext.getUserId(), req.getActivityId()));
    }

    /** 好友帮砍（同一帮砍人对同一记录仅一刀）。 */
    @PostMapping("/{recordId}/help")
    public Result<HelpCutVO> help(@PathVariable @Positive(message = "记录ID必须为正数") Long recordId) {
        return Result.success(bargainService.helpCut(UserContext.getUserId(), recordId));
    }

    /** 砍价详情：当前价/底价/剩余时间/帮砍列表。 */
    @GetMapping("/{recordId}")
    public Result<BargainDetailVO> detail(
            @PathVariable @Positive(message = "记录ID必须为正数") Long recordId) {
        return Result.success(bargainService.detail(recordId));
    }
}
