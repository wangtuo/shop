package com.shop.user.profile.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 用户实体（t_user）。注册/登录/会员状态/签到连续天数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {

    /** 登录用户名 */
    private String username;

    /** 手机号（唯一登录账号） */
    private String phone;

    /** BCrypt 加盐哈希 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 用户类型：-1 游客 0 普通 1 商户 2 平台运营 */
    private Integer userType;

    /** 商户 ID（userType=1 时有值） */
    private Long merchantId;

    /** 账户状态：0 正常 1 冻结 2 注销 */
    private Integer status;

    /** 成长值 */
    private Long growth;

    /** 会员等级 0-4 */
    private Integer level;

    /** 最近一次签到日期 */
    private LocalDate lastSignDate;

    /** 当前连续签到天数 */
    private Integer continuousDays;

    @Version
    private Integer version;
}
