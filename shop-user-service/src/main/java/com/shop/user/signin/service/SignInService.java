package com.shop.user.signin.service;

import com.shop.user.signin.dto.SignInResult;

import java.time.LocalDate;

/**
 * 签到服务：连续签到中断重置；第 1 天 5 … 第 7 天 50；每 7 天 +50 成长值。
 */
public interface SignInService {

    /** 当前用户今日签到（当日重复签到幂等返回当日结果） */
    SignInResult sign(Long userId);

    /** 指定日期签到（定时/单测固定时钟用） */
    SignInResult sign(Long userId, LocalDate today);
}
