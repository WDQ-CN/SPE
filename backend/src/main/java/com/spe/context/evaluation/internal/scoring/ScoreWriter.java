package com.spe.context.evaluation.internal.scoring;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.ScoreSource;
import com.spe.context.evaluation.api.SheetStatus;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetContextRow;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreStrategies;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评分写入的**唯一入口**（13 §E7 / 14 §W2.4 的口径）：手工暂存与 Excel 导入都只调它，
 * 开放 API（M6）将来也只是加一个薄委托。
 *
 * <p>它负责四件事，顺序即语义：
 * <ol>
 *   <li><b>守卫</b>：只有 `SCORING` 的评分单可写（"已提交""待指派"一律拒），0 行/状态不符 → 2xxx/3xxx；
 *   <li><b>换算</b>：用任务的**已发布快照**把原始值换算成百分制分（13 §E8：`score_value` 随写随落，
 *       不是提交时才算——否则暂存期间看板读不到分）；
 *   <li><b>校验</b>：越界/精度/定性非整数一律翻成 `SCORE_VALUE_INVALID(2025)`；
 *       ⚠️ 这条是 04-072 的教训——Excel 导入**不过 DTO**，`@Digits`/`@Size` 拦不到，必须在服务层拦；
 *   <li><b>upsert</b>：按 `uk_score(sheet_id, indicator_id)` 幂等覆盖（01 §6.7 的"重复导入覆盖"）。
 * </ol>
 *
 * <p>⚠️ 审计不在这里写：留痕属于业务动作（暂存/导入/提交），由调用方在**动作语义**处写，
 * 否则一次导入会变成 N 条审计（13 §E20 的动作码是按动作分的，不是按格）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreWriter {

  private final ScoreMapper scoreMapper;
  private final SheetMapper sheetMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;

  /**
   * 写入一张评分单的若干指标（幂等 upsert）。
   *
   * @param sheetId 评分单
   * @param rawValues 指标 id → 原始值（**不传的格子不动**，不是清空）
   * @param source 写入渠道（MANUAL / IMPORT / API）
   * @param importBatch Excel 批次号；`source != IMPORT` 时必须传 null（否则会把旧批次号带进来）
   * @param operatorId 调用者 uid：**状态与负责人都在这里判**（04-096），调用方不必自己先判一遍
   * @return 实际写入的格子数
   */
  @Transactional
  public int save(
      long sheetId,
      Map<Long, BigDecimal> rawValues,
      ScoreSource source,
      String importBatch,
      long operatorId) {
    if (rawValues == null || rawValues.isEmpty()) {
      return 0;
    }
    if (source != ScoreSource.IMPORT && importBatch != null) {
      throw new IllegalArgumentException("只有 IMPORT 来源才允许带批次号：" + source);
    }
    List<Score> rows = buildRows(sheetId, rawValues, operatorId);
    if (rows.isEmpty()) {
      return 0;
    }
    // 来源与批次号只在这条路径上写（`correctValues` 刻意不碰它们）
    rows.forEach(
        row -> {
          row.setSource(source.name());
          row.setImportBatch(source == ScoreSource.IMPORT ? importBatch : null);
        });
    scoreMapper.upsertBatch(rows);
    // ⚠️ 返回**行数**而不是 JDBC 影响行数：upsert 的"更新"记 2、"值没变"记 0，直接用会让
    // "已保存 N 个指标 / 导入写入 N 格"这两个提示失真（03 §34 的 P2-6）。
    log.info("写入评分 {} 格（sheetId={}，source={}，batch={}）", rows.size(), sheetId, source, importBatch);
    return rows.size();
  }

  /**
   * **只纠正分值**的写入（04-096 / `03 §34` P1-2）：不动 `source` 与 `import_batch`。
   *
   * <p>给"提交时发现库里的 `score_value` 与重算不一致"的回写用：那时行**一定已存在**，
   * 被纠正的是**算错了的分**，而来源是**历史事实**（导入还是手工）。原先那条回写复用 {@link #save}，
   * 会把 `source` 改成 `MANUAL`、把 `import_batch` 清成 NULL ⇒ 01 §6.7 的"这批覆盖了哪些"再也查不出来。
   */
  @Transactional
  public int correctValues(long sheetId, Map<Long, BigDecimal> rawValues, long operatorId) {
    if (rawValues == null || rawValues.isEmpty()) {
      return 0;
    }
    List<Score> rows = buildRows(sheetId, rawValues, operatorId);
    int corrected = 0;
    for (Score row : rows) {
      // 逐行 UPDATE（不是 upsert）：行一定已存在，且**只为纠值**——来源与批次号原样留着。
      // 这条路径只在"库里分值重算不一致"时触发（罕见），行数是该评分单的指标数（十几以内）。
      corrected +=
          scoreMapper.updateValues(sheetId, row.getIndicatorId(), row.getRawValue(), row.getScoreValue());
    }
    if (corrected > 0) {
      log.warn("按快照纠正 {} 格的重算分（sheetId={}，来源与批次号保持不变）", corrected, sheetId);
    }
    return corrected;
  }

  /**
   * 守卫 + 换算，产出待写入的行（`save` 与 `correctValues` 共用；`source`/`importBatch` 由调用方填）。
   *
   * <p>⚠️ **锁定读是本事务的第一条语句**（CLAUDE 默认约定第 6 条）：「状态与负责人」一律取当前值——
   * 用普通读会把"刚被别人提交"的评分单看成仍可写，于是给一张 SUBMITTED 的单子落下新分（04-096）。
   * 只锁 sheet 本行：`submit` 是「先 task_supplier、再 sheet」，本方法不请求 task_supplier，不成环。
   */
  private List<Score> buildRows(long sheetId, Map<Long, BigDecimal> rawValues, long operatorId) {
    Sheet sheet =
        sheetMapper.findByIdForUpdate(sheetId).orElseThrow(() -> new BizException(ErrorCode.SHEET_NOT_FOUND));
    requireWritable(sheet, operatorId);
    SheetContextRow context =
        sheetMapper.findContextById(sheetId).orElseThrow(() -> new BizException(ErrorCode.SHEET_NOT_FOUND));

    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(context.getTemplateVersionId());
    Map<Long, IndicatorSnapshot> indicatorsById = new HashMap<>();
    for (var dimension : snapshot.dimensions()) {
      for (IndicatorSnapshot indicator : dimension.indicators()) {
        indicatorsById.put(indicator.id(), indicator);
      }
    }

    List<Score> rows = new ArrayList<>(rawValues.size());
    for (Map.Entry<Long, BigDecimal> entry : rawValues.entrySet()) {
      Long indicatorId = entry.getKey();
      BigDecimal rawValue = entry.getValue();
      IndicatorSnapshot indicator = indicatorsById.get(indicatorId);
      if (indicator == null) {
        // 指标不在这张评分单绑定的模板版本里 ⇒ 拒绝（不是"忽略这一格"，否则导入会静默漏数据）
        throw new BizException(
            ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND,
            "指标不属于该评分单绑定的模板版本：indicatorId=" + indicatorId + "，versionId=" + snapshot.versionId());
      }
      if (rawValue == null) {
        continue;
      }
      Score score = new Score();
      score.setSheetId(sheetId);
      score.setIndicatorId(indicatorId);
      score.setRawValue(rawValue);
      score.setScoreValue(convert(rawValue, indicator));
      rows.add(score);
    }
    return rows;
  }

  /** 换算 + 输入校验：策略抛的 IllegalArgumentException 在这里翻成 2025（01 §6.6 的"后端拒绝"）。 */
  private static BigDecimal convert(BigDecimal rawValue, IndicatorSnapshot indicator) {
    try {
      return ScoreStrategies.of(indicator).toHundred(rawValue, indicator);
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.SCORE_VALUE_INVALID, ex.getMessage());
    }
  }

  /**
   * 写分的**单入口守卫**（04-096）：状态与负责人一次判完。
   *
   * <p>为什么 assignee 也放在这里、而不是留给调用方：写分有**两条路径**（手工暂存 / Excel 导入），
   * 而"只有被指派人可写"这条不变量原先只被前者之外的 `ScoringService.requireReadableSheet` 覆盖，
   * 导入那条**整个漏掉了**（越权实测见 `03 §34`）。合成一个入口后，漏用要么编不过（少传 operatorId）、
   * 要么必然 4002——不再依赖"调用方记得先判"。
   */
  private static void requireWritable(Sheet sheet, long operatorId) {
    if (sheet.getStatus() != SheetStatus.SCORING) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID,
          "只有「评分中」的评分单可以写分，当前状态：" + sheet.getStatus());
    }
    if (sheet.getAssigneeId() == null || sheet.getAssigneeId() != operatorId) {
      throw new BizException(ErrorCode.SHEET_NOT_ACCESSIBLE);
    }
  }
}
