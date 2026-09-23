package com.shop.user.share.service;

import com.shop.user.share.dto.ShareCompleteRequest;
import com.shop.user.share.dto.ShareResultVO;

/**
 * 分享激励（B6-b）：分享完成回调落 t_user_share_log 并发积分（10/次，日限 20 clamp）；不发成长值。
 */
public interface ShareService {

    /**
     * 分享完成回调。requestNo 幂等：重复回调返回旧 pointsEarned，不重复入账；
     * 当日积分达上限时 pointsEarned=0，接口仍成功。
     */
    ShareResultVO complete(Long userId, ShareCompleteRequest request);
}
