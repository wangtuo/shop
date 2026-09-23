package com.shop.product.comment.service.impl;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.product.comment.service.SensitiveWordService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 内置敏感词表组件（无外部依赖，可后续替换为词库/DFA 实现）。
 * <ul>
 *   <li>硬违禁词：违法违规内容，命中直接拒绝评价；</li>
 *   <li>轻度敏感词：极限词/广告词，命中替换为等长 *，允许提交。</li>
 * </ul>
 */
@Service
public class SensitiveWordServiceImpl implements SensitiveWordService {

    /** 硬违禁词：命中拒绝 */
    private static final List<String> HARD_WORDS = List.of(
            "赌博", "枪支", "弹药", "毒品", "走私", "反动", "诈骗", "色情");

    /** 轻度敏感词（广告极限词）：命中替换 */
    private static final List<String> SOFT_WORDS = List.of(
            "最便宜", "最低价", "国家级", "最高级", "最佳", "第一品牌", "绝无仅有", "极品", "万能");

    @Override
    public String checkAndFilter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        for (String hard : HARD_WORDS) {
            if (text.contains(hard)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "内容包含违禁敏感词，请修改后再发布");
            }
        }
        String filtered = text;
        for (String soft : SOFT_WORDS) {
            if (filtered.contains(soft)) {
                filtered = filtered.replace(soft, "*".repeat(soft.length()));
            }
        }
        return filtered;
    }

    @Override
    public boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String word : HARD_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        for (String word : SOFT_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
