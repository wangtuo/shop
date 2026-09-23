package com.shop.user.account.controller;

import com.shop.api.user.enums.AccountTypes;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.user.account.dto.PointsAccountVO;
import com.shop.user.account.entity.UserAccountFlow;
import com.shop.user.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端账户查询：积分账户、积分流水、余额/赠金流水。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** 我的积分账户（可用 + 冻结） */
    @GetMapping("/points")
    public Result<PointsAccountVO> points() {
        return Result.success(accountService.getPointsAccount(UserContext.getUserId()));
    }

    /** 我的积分流水分页 */
    @GetMapping("/points/flows")
    public Result<PageResult<UserAccountFlow>> pointsFlows(PageQuery query) {
        return Result.success(accountService.pageFlows(UserContext.getUserId(), AccountTypes.POINTS, query));
    }

    /**
     * 我的余额/赠金流水分页。
     *
     * @param accountType 1 余额（默认）2 赠金
     */
    @GetMapping("/balance/flows")
    public Result<PageResult<UserAccountFlow>> balanceFlows(@RequestParam(value = "accountType", defaultValue = "1")
                                                            Integer accountType,
                                                            PageQuery query) {
        return Result.success(accountService.pageFlows(UserContext.getUserId(), accountType, query));
    }
}
