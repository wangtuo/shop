package com.shop.aftersale.support;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 售后超时延时消息体（P2-1）：继承统一事件信封，自带<b>确定性</b> eventId。
 *
 * <p>同一业务超时动作（同售后单/理赔单 + 同 kind + 同轮次）无论重发几次，eventId 恒定，
 * MQ 延时消息与 60s 扫表双路径据此命中同一条 t_aftersale_mq_consume 流水实现消息级幂等。</p>
 *
 * <p><b>R4-25 轮次维度（roundKey）：</b>同一售后单会两次进入同一状态——拒绝(55)后修改
 * 重提重新进入待审核(10)、换货单平台仲裁买家胜诉重新进入待换货发货(41)。每轮进入都会
 * 重新登记延时事件，而 outbox 唯一键 uk_topic_tag_bizkey(topic,tag,biz_key) 行投递后永不
 * 删除，旧设计第二轮插入必抛 DuplicateKeyException 回滚整个业务事务（重提/仲裁失败、
 * 事件重试 16 次进 DLQ）。轮次键取该轮截止点的 epoch 毫秒：登记事务与扫表读取的是同一
 * 个 DATETIME 列值，现算结果一致；不同轮次截止点不同，eventId/outbox bizKey 自然不同。
 * bizKey 形如 {@code ASxxx#R1789000000000}，eventId 形如 {@code TO-ASxxx-audit-R1789…}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AftersaleTimeoutMessage extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 售后单号（运费险超时可为空，使用 insuranceId） */
    private String aftersaleNo;

    /** 超时种类，见 AftersaleDelayTopics.KIND_* */
    private String kind;

    /** 运费险记录 ID（kind=insurance 时使用） */
    private Long insuranceId;

    /** R4-25：超时轮次键（该轮截止点 epochMilli），null 表示无轮次维度（首轮/保险/举证）。 */
    private String roundKey;

    /** 反序列化/历史测试用三参构造（等价于无轮次维度）。 */
    public AftersaleTimeoutMessage(String aftersaleNo, String kind, Long insuranceId) {
        this(aftersaleNo, kind, insuranceId, null);
    }

    /** 关单/流转类超时（audit/receive/exchange_ship/evidence），首轮（无轮次维度）。 */
    public static AftersaleTimeoutMessage forAftersale(String aftersaleNo, String kind) {
        return forAftersale(aftersaleNo, kind, null);
    }

    /** 关单/流转类超时，带轮次键（同一状态第二轮及以后的延时登记/扫表）。 */
    public static AftersaleTimeoutMessage forAftersale(String aftersaleNo, String kind, String roundKey) {
        AftersaleTimeoutMessage msg = new AftersaleTimeoutMessage(aftersaleNo, kind, null, roundKey);
        msg.assignEvent(deriveId(aftersaleNo, kind, null, roundKey), aftersaleNo);
        return msg;
    }

    /** 运费险理赔类超时：insurance 记录以 id 唯一（UK t_aftersale_insurance.order_no 每单一次）。 */
    public static AftersaleTimeoutMessage forInsurance(Long insuranceId, String aftersaleNo, String kind) {
        AftersaleTimeoutMessage msg = new AftersaleTimeoutMessage(aftersaleNo, kind, insuranceId, null);
        msg.assignEvent(deriveId(aftersaleNo, kind, insuranceId, null), aftersaleNo);
        return msg;
    }

    /**
     * 轮次键：该轮超时截止点的 epoch 毫秒字符串。登记延时的业务事务与 60s 扫表作业读取
     * 同一 DATETIME 列（秒精度），在同一部署时区下现算结果逐字一致。
     */
    public static String deadlineRoundKey(LocalDateTime deadline) {
        if (deadline == null) {
            return null;
        }
        return Long.toString(deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    /** outbox bizKey：首轮裸售后单号（保持历史口径），带轮次时追加 {@code #R}{roundKey}。 */
    public static String outboxBizKey(String aftersaleNo, String roundKey) {
        if (roundKey == null || roundKey.isBlank()) {
            return aftersaleNo;
        }
        return aftersaleNo + "#R" + roundKey;
    }

    /**
     * 确定性 eventId 现算（历史无信封消息兜底）：
     * 关单类 {@code TO-{aftersaleNo}-{kind}}；保险类 {@code TO-INS-{insuranceId}-{kind}}；
     * 带轮次时追加 {@code -R{roundKey}}。
     */
    public static String deriveId(String aftersaleNo, String kind, Long insuranceId) {
        return deriveId(aftersaleNo, kind, insuranceId, null);
    }

    public static String deriveId(String aftersaleNo, String kind, Long insuranceId, String roundKey) {
        String base;
        if (insuranceId != null) {
            base = "TO-INS-" + insuranceId + "-" + kind;
        } else {
            base = "TO-" + aftersaleNo + "-" + kind;
        }
        return (roundKey == null || roundKey.isBlank()) ? base : base + "-R" + roundKey;
    }

    private void assignEvent(String eventId, String bizNo) {
        setEventId(eventId);
        setBizNo(bizNo);
    }
}
