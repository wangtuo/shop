package com.shop.product.comment.service;

import com.shop.common.exception.BizException;

/**
 * 内置敏感词过滤组件：命中硬违禁词直接拒绝，命中广告词等轻度敏感词替换为等长星号。
 */
public interface SensitiveWordService {

    /**
     * 检查并过滤文本。
     *
     * @param text 原始文本
     * @return 轻度敏感词被替换后的文本；无敏感词时原样返回
     * @throws BizException 命中硬违禁词时抛 PARAM_INVALID
     */
    String checkAndFilter(String text);

    /** 仅判定是否包含任意敏感词（硬/软）。 */
    boolean containsSensitive(String text);
}
