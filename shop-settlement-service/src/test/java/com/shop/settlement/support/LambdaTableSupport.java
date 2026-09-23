package com.shop.settlement.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.settlement.account.entity.SettAccount;
import com.shop.settlement.account.entity.SettAccountFlow;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.mq.entity.SettMqConsume;
import com.shop.settlement.statement.entity.SettStatement;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.entity.SettWithdrawAutoConfig;
import com.shop.settlement.withdraw.entity.SettWithdrawDailyCount;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯 Mockito 单测中直接构造 LambdaUpdateWrapper#set(SFunction) 时，
 * MyBatis-Plus 的实体表信息缓存不会由 SqlSessionFactory 初始化，
 * 这里手工注册全部实体，避免 "can not find lambda cache"。
 */
public final class LambdaTableSupport {

    private LambdaTableSupport() {
    }

    private static volatile boolean initialized;

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SettMerchant.class);
        TableInfoHelper.initTableInfo(assistant, SettAccount.class);
        TableInfoHelper.initTableInfo(assistant, SettAccountFlow.class);
        TableInfoHelper.initTableInfo(assistant, SettClearing.class);
        TableInfoHelper.initTableInfo(assistant, SettClearingReverse.class);
        TableInfoHelper.initTableInfo(assistant, SettStatement.class);
        TableInfoHelper.initTableInfo(assistant, SettWithdraw.class);
        TableInfoHelper.initTableInfo(assistant, SettWithdrawDailyCount.class);
        TableInfoHelper.initTableInfo(assistant, SettWithdrawAutoConfig.class);
        TableInfoHelper.initTableInfo(assistant, SettDepositLog.class);
        TableInfoHelper.initTableInfo(assistant, SettMqConsume.class);
        initialized = true;
    }
}
