package com.shop.user.share.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分享完成回调返回（C44）：回传客户端幂等号与本次实际入账积分（日限打满为 0；重复 requestNo 回传旧值）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestNo;

    /** 本次实际入账积分（日限 clamp 后可能为 0） */
    private Long pointsEarned;
}
