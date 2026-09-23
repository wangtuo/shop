package com.shop.settlement.merchant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清算商户（t_sett_merchant）：等级 / 类目默认佣金率 / 保证金。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_merchant")
public class SettMerchant extends BaseEntity {

    /** 商户名称 */
    private String merchantName;
    /** 等级：0 S 1 A 2 B 3 C */
    private Integer merchantLevel;
    /** 主营三级类目 ID */
    private Long categoryId;
    /** 主营类目名称 */
    private String categoryName;
    /** 类目默认佣金率（bps） */
    private Integer commissionRateBps;
    /** 保证金余额（分） */
    private Long depositBalanceFen;
    /** 应缴保证金（分，按类目 1000~50000 元） */
    private Long depositRequiredFen;
    /** 已发低额预警：0 否 1 是 */
    private Integer depositAlerted;
    /** 状态：0 禁用 1 正常 2 清退中 3 已清退 */
    private Integer status;
    /** 清退登记时间 */
    private LocalDateTime resignTime;

    @Version
    private Integer version;
}
