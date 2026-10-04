package com.spe.context.evaluation.internal.scoring.calculator;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个评估单（跨全部维度）的结果（13 §E5 / §E6），供进度看板（J3）与 P4 的定稿复用。
 *
 * <p>{@code complete = false}（任一维度不完整）时 {@code totalScore} 与 {@code totalGrade} 为 null：
 * 总分是"各维度分按权重加权"，缺一个维度就没有意义。
 *
 * @param sheets 各维度（评分单）的结果，按快照顺序
 * @param totalScore 总分（2 位，HALF_UP）；不完整时为 null
 * @param totalGrade 总等级 A/B/C/D；不完整时为 null
 * @param complete 是否全部维度都完整
 */
public record TaskSupplierScore(
    List<ScoreSheetResult> sheets, BigDecimal totalScore, String totalGrade, boolean complete) {}
