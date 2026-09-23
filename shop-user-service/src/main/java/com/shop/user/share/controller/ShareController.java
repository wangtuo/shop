package com.shop.user.share.controller;

import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.user.share.dto.ShareCompleteRequest;
import com.shop.user.share.dto.ShareResultVO;
import com.shop.user.share.service.ShareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端分享完成回调（C44）：POST /users/shares（网关全路径 /api/user/users/shares）。
 * 登录强制 X-User-Id（AuthInterceptor 拦未登录 401）；requestNo 客户端幂等。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @PostMapping("/shares")
    public Result<ShareResultVO> complete(@Valid @RequestBody ShareCompleteRequest request) {
        return Result.success(shareService.complete(UserContext.getUserId(), request));
    }
}
