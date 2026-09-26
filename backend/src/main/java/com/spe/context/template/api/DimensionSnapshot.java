package com.spe.context.template.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 维度快照（12 §E16）：发布版本不可变快照的一部分。
 *
 * @param id 维度 id
 * @param name 维度名称（如质量）
 * @param weight **模板内**权重（维度权重合计须为 100，01 T4）
 * @param isKey 是否关键维度；供预警规则②（01 W1）使用——预警在 M5，但字段随快照一次定死，
 *     免得 P3 定完契约后再回头加字段（那时迁移已冻结）
 * @param sortNo 排序号
 * @param suggestedDept 建议评估部门：**01 J2 要求任务发布时按它自动指派该部门下的评估人**，
 *     所以它必须在快照里，让 P3 从快照直接取、不再回查模板表
 * @param indicators 该维度下的指标（按 {@code sort_no} 排序）
 */
public record DimensionSnapshot(
    long id,
    String name,
    BigDecimal weight,
    boolean isKey,
    int sortNo,
    String suggestedDept,
    List<IndicatorSnapshot> indicators) {
}
