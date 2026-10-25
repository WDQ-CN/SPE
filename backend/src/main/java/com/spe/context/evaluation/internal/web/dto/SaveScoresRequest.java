package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 暂存打分（01 E2 / 13 §E18 的 `PUT /api/sheets/{id}/scores`）：**只传本次改动的格子**。
 *
 * <p>⚠️ 一条格子有两种"改动"，语义必须分开（03 §38 的 P2-15，18 §6 裁决点 6 拍定）：
 * <ul>
 *   <li>`rawValue` 有值 ⇒ 写入/覆盖这一格；
 *   <li>`rawValue` 显式为 `null` ⇒ **清空**这一格（库里删掉行）；
 *   <li>这一格**整个不在请求里** ⇒ 不动它（既不是清空，也不是未填）。
 * </ul>
 * 原先 `rawValue` 挂着 {@code @NotNull}，"清空"在契约层根本表达不了，表现是"删值 → 提示已保存 →
 * 刷新后旧值复活"。前端要用 `touchedIds` 区分"从没填过"与"填过又清空"，见
 * {@code SheetScoringView.vue} 与 `02 §4`。
 *
 * <p>`rawValue` 的 `@Digits` 是**第一层**（快速失败、错误信息好看）；真正的边界判定在
 * `ScoreWriter`→`ScoreStrategies`（同一套规则，Excel 导入也走它，04-072 的教训）。
 */
public record SaveScoresRequest(
    @NotNull(message = "缺少评分内容") @Size(max = 200, message = "一次最多提交 200 个指标")
        List<@Valid Entry> scores) {

  /** 一格。`rawValue` 为 null 表示"清空这一格"。 */
  public record Entry(
      @Positive(message = "指标不合法") long indicatorId,
      @Digits(integer = 8, fraction = 2, message = "录入值最多两位小数") BigDecimal rawValue) {}
}
