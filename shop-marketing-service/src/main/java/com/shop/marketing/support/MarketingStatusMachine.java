package com.shop.marketing.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * 营销三表状态机（卡 B2 + API-M 共用，唯一定义点，禁止另造白名单）。
 *
 * <p>两层状态：</p>
 * <ol>
 *   <li>审核态 audit_status：0 草稿 → 1 待审核 → 2 通过 / 3 驳回；驳回后允许改后再提交（3→1）。</li>
 *   <li>业务态 status（各表原语义）：所有变更只允许在 audit_status=2（通过）后进行；
 *       枚举值不在白名单（如 99）、非法迁移（如券作废 2→1 复活、活动已结束 2→1）一律拒绝。</li>
 * </ol>
 *
 * <p>业务态边（到点自动结束由 W4-4 SeckillAutoEndJob 直接 CAS，语义必须与
 * {@link Domain#ACTIVITY} 白名单一致，本类仅消费其状态语义）：</p>
 * <ul>
 *   <li>活动：0 下架 ⇄ 1 进行中；1→2 已结束（到点 Job/平台）；0/1→3 已取消；2/3 终态。</li>
 *   <li>促销：0 停用 ⇄ 1 启用。</li>
 *   <li>券：0 下架 ⇄ 1 上架；0/1→2 作废（不可逆）。</li>
 * </ul>
 */
public final class MarketingStatusMachine {

    /** 营销实体域。 */
    public enum Domain {
        ACTIVITY,
        PROMO,
        COUPON
    }

    /** 审核态合法迁移：0→1 提交、3→1 驳回后重新提交、1→2 通过、1→3 驳回。 */
    private static final Map<Integer, Set<Integer>> AUDIT_EDGES = Map.of(
            MarketingAuditStatus.DRAFT.getCode(), Set.of(MarketingAuditStatus.PENDING.getCode()),
            MarketingAuditStatus.PENDING.getCode(), Set.of(
                    MarketingAuditStatus.APPROVED.getCode(), MarketingAuditStatus.REJECTED.getCode()),
            MarketingAuditStatus.REJECTED.getCode(), Set.of(MarketingAuditStatus.PENDING.getCode())
    );

    /** 业务态合法迁移（按域）。 */
    private static final Map<Domain, Map<Integer, Set<Integer>>> BIZ_EDGES = Map.of(
            Domain.ACTIVITY, Map.of(
                    0, Set.of(1, 3),
                    1, Set.of(0, 2, 3)
            ),
            Domain.PROMO, Map.of(
                    0, Set.of(1),
                    1, Set.of(0)
            ),
            Domain.COUPON, Map.of(
                    0, Set.of(1, 2),
                    1, Set.of(0, 2)
            )
    );

    private MarketingStatusMachine() {
    }

    /** 校验审核态迁移合法（提交/通过/驳回），非法抛 PARAM_INVALID。 */
    public static void assertAuditTransition(int fromAudit, int toAudit) {
        Set<Integer> allowed = AUDIT_EDGES.get(fromAudit);
        if (allowed == null || !allowed.contains(toAudit)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "非法审核状态迁移: " + fromAudit + "→" + toAudit);
        }
    }

    /** 是否处于可提交态（草稿 0 / 驳回 3）。 */
    public static boolean canSubmit(int auditStatus) {
        return auditStatus == MarketingAuditStatus.DRAFT.getCode()
                || auditStatus == MarketingAuditStatus.REJECTED.getCode();
    }

    /**
     * 校验业务态（上下架/作废/结束/取消）变更。
     * <ul>
     *   <li>目标态非白名单枚举（如 99）→ PARAM_INVALID；</li>
     *   <li>迁移不在白名单（作废复活、终态回退等）→ PARAM_INVALID；</li>
     *   <li>审核未通过（auditStatus≠2）→ CONFLICT "未通过审核"。</li>
     * </ul>
     */
    public static void assertBizTransition(Domain domain, Integer auditStatus, int fromBiz, int toBiz) {
        if (auditStatus == null || auditStatus != MarketingAuditStatus.APPROVED.getCode()) {
            throw new BizException(ErrorCode.CONFLICT, "未通过审核，不能变更上下架状态");
        }
        Map<Integer, Set<Integer>> edges = BIZ_EDGES.get(domain);
        Set<Integer> allowed = edges.get(fromBiz);
        if (allowed == null || !allowed.contains(toBiz)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "非法状态迁移: " + fromBiz + "→" + toBiz);
        }
    }
}
