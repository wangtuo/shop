package com.shop.settlement.remit;

import lombok.Builder;
import lombok.Data;

/**
 * 代发（出款）请求：商户提现打款 / 保证金清退退还统一走本 SPI（GAP_PLAN_FUNDS B10）。
 *
 * <p>渠道幂等键 = 业务单号 {@link #bizNo}（提现 withdrawNo / 保证金退还 logNo）：
 * 渠道侧必须以该键做幂等，批次重试/查询补偿重复提交不得造成重复代发。
 * 收款账号/姓名为 DataCipher 解密后的明文，仅在调用瞬间存在于内存，禁止落日志。</p>
 */
@Data
@Builder
public class RemitRequest {

    /** 渠道幂等键：提现 withdrawNo 或保证金退还 logNo */
    private String bizNo;
    private Long merchantId;
    /** 渠道：见 {@link com.shop.settlement.enums.WithdrawChannels}（1 银行卡 2 支付宝） */
    private Integer channel;
    /** 收款账号（明文） */
    private String channelAccount;
    /** 收款人姓名（明文） */
    private String accountName;
    /** 开户行（支付宝场景为空串） */
    private String bankName;
    /** 代发金额（分，必须为正） */
    private Long amountFen;
    /** 附言/备注 */
    private String remark;
}
