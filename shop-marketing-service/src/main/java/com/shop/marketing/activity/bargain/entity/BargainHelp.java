package com.shop.marketing.activity.bargain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 砍价帮砍留痕（t_bargain_help，V5）。
 * 注意：该表只有 create_time、无 update_time，故不继承 {@code BaseEntity}（避免 MP 填充不存在的列）。
 * uk_record_helper(record_id, helper_user_id, deleted)：同一帮砍人对同一记录仅一次。
 */
@Data
@TableName("t_bargain_help")
public class BargainHelp implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long recordId;
    private Long helperUserId;
    /** 本次实际砍下金额（分，已按剩余可砍金额收敛） */
    private Long cutFen;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
