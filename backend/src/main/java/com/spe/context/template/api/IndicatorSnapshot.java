package com.spe.context.template.api;

import java.math.BigDecimal;

/**
 * 指标快照（12 §E16）：发布版本不可变快照的一部分，供 P3 的 {@code ScoreCalculator} 与打分页渲染消费。
 *
 * <p>字段刻意只含**计分与渲染必需**的部分——模板上下文的内部结构对下游不可见（05 ADR-3）；P3 需要新
 * 字段时走"新增字段"而不是改既有语义。
 *
 * @param id 指标 id（评分记录按它关联）
 * @param code 指标编码：Excel 导入与开放 API 的定位键（01 T3）
 * @param name 指标名称
 * @param weight **维度内**权重（不是模板内权重：附录 A 的指标权重是各维度内合计 100，12 §R-1）
 * @param type 定量 / 定性
 * @param scoreMode 计分方式；定性指标为 {@code null}
 * @param unit 单位（附录 A 未给该列，按"说明"列推导，12 §E15）
 * @param targetValue 目标值，仅 {@link ScoreMode#TARGET} 有意义；发布时校验 &gt; 0（01 §6.6）
 * @param standard 评分标准说明：定性指标的档位文案、DIRECT 指标的人工换算表
 */
public record IndicatorSnapshot(
    long id,
    String code,
    String name,
    BigDecimal weight,
    IndicatorType type,
    ScoreMode scoreMode,
    String unit,
    BigDecimal targetValue,
    String standard) {
}
