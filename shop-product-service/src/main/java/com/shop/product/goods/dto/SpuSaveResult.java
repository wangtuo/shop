package com.shop.product.goods.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 建品/编辑保存结果（B13）：SPU ID + 属性主数据校验告警集合。
 * 告警不阻断保存（attrsJson 原样进快照，向后兼容）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpuSaveResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SPU ID（编辑时为入参 spuId） */
    private Long spuId;

    /** 属性校验/未收录告警（空列表表示无告警） */
    private List<String> warnings = new ArrayList<>();

    public static SpuSaveResult of(Long spuId, List<String> warnings) {
        return new SpuSaveResult(spuId, warnings == null ? new ArrayList<>() : warnings);
    }
}
