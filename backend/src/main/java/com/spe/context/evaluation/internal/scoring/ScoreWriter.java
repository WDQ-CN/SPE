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
 * <p>它负责五件事，顺序即语义：
 * <ol>
 *   <li><b>守卫</b>：只有 `SCORING` 的评分单可写（"已提交""待指派"一律拒），0 行/状态不符 → 2xxx/3xxx；
 *   <li><b>换算</b>：用任务的**已发布快照**把原始值换算成百分制分（13 §E8：`score_value` 随写随落，
 *       不是提交时才算——否则暂存期间看板读不到分）；
 *   <li><b>校验</b>：越界/精度/定性非整数一律翻成 `SCORE_VALUE_INVALID(2025)`；
 *       ⚠️ 这条是 04-072 的教训——Excel 导入**不过 DTO**，`@Digits`/`@Size` 拦不到，必须在服务层拦；
 *   <li><b>upsert</b>：按 `uk_score(sheet_id, indicator_id)` 幂等覆盖（01 §6.7 的"重复导入覆盖"）；
 *   <li><b>清空</b>：调用方点名的指标可以删掉行（03 §38 的 P2-15——"删值 → 保存 → 旧值复活"以前是
 *       契约层表达不了的事）。⚠️ **只有手工路径用得上**：01 §6.6/§6.7 规定 Excel 的空格"视为该格未填"，
 *       所以导入传进来的清空集合恒为空。
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
   * 写入一张评分单的若干指标（幂等 upsert ＋ 显式清空）。
   *
   * @param sheetId 评分单
   * @param rawValues 指标 id → 原始值（**不在 keys 里的格子不动**）
   * @param clearIndicatorIds 要**清空**的指标（01 E2 的"删值"，03 §38 的 P2-15）；
   *     它们同样要经过"指标属于本快照 ＋ 单子可写 ＋ 是指派给我"三道守卫
   * @param source 写入渠道（MANUAL / IMPORT / API）
   * @param importBatch Excel 批次号；`source != IMPORT` 时必须传 null（否则会把旧批次号带进来）
   * @param operatorId 调用者 uid：**状态与负责人都在这里判**（04-096），调用方不必自己先判一遍
   * @return 本次**实际改动**的格子数（写入数 ＋ 删除数）；删一个本来就没值的格子记 0，不算改动
   */
  @Transactional
  public int save(
      long sheetId,
      Map<Long, BigDecimal> rawValues,
      List<Long> clearIndicatorIds,
      ScoreSource source,
      String importBatch,
      long operatorId) {
    if (source != ScoreSource.IMPORT && importBatch != null) {
      throw new IllegalArgumentException("只有 IMPORT 来源才允许带批次号：" + source);
    }
    if (isEmpty(rawValues) && isEmpty(clearIndicatorIds)) {
      return 0;
    }
    WritePlan plan = plan(sheetId, rawValues, clearIndicatorIds, operatorId, WriteMode.USER);
    List<Score> rows = plan.rows();
    int written = 0;
    if (!rows.isEmpty()) {
      // 来源与批次号只在这条路径上写（`correctValues` 刻意不碰它们）
      rows.forEach(
          row -> {
            row.setSource(source.name());
            row.setImportBatch(source == ScoreSource.IMPORT ? importBatch : null);
          });
      scoreMapper.upsertBatch(rows);
      written = rows.size();
    }
    int cleared =
        plan.clearIndicatorIds().isEmpty()
            ? 0
            : scoreMapper.deleteValues(sheetId, plan.clearIndicatorIds());
    if (written + cleared > 0) {
      log.info(
          "写入评分 {} 格、清空 {} 格（sheetId={}，source={}，batch={}）",
          written,
          cleared,
          sheetId,
          source,
          importBatch);
    }
    // ⚠️ 返回**行数**而不是 JDBC 影响行数：upsert 的"更新"记 2、"值没变"记 0，直接用会让
    // "已保存 N 个指标 / 导入写入 N 格"这两个提示失真（03 §34 的 P2-6）。
    return written + cleared;
  }

  private static boolean isEmpty(Map<Long, BigDecimal> values) {
    return values == null || values.isEmpty();
  }

  private static boolean isEmpty(List<Long> ids) {
    return ids == null || ids.isEmpty();
  }

  /**
   * **只纠正分值**的写入（04-096 / `03 §34` P1-2）：不动 `source` 与 `import_batch`，也不删任何格子。
   *
   * <p>给"提交时发现库里的 `score_value` 与重算不一致"的回写用：那时行**一定已存在**，
   * 被纠正的是**算错了的分**，而来源是**历史事实**（导入还是手工）。原先那条回写复用 {@link #save}，
   * 会把 `source` 改成 `MANUAL`、把 `import_batch` 清成 NULL ⇒ 01 §6.7 的"这批覆盖了哪些"再也查不出来。
   */
  @Transactional
  public int correctValues(long sheetId, Map<Long, BigDecimal> rawValues, long operatorId) {
    if (isEmpty(rawValues)) {
      return 0;
    }
    List<Score> rows = plan(sheetId, rawValues, List.of(), operatorId, WriteMode.USER).rows();
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
   * 开放 API 的写入口（M6 / 23 §2 D7）：与手工/导入共用 {@link #plan} 的指标归属、快照换算与 upsert，
   * 只放宽「必须 SCORING + 必须指派人」这条人工守卫——机器没有用户，`UNASSIGNED` 也尚未提交。
   */
  @Transactional
  public BigDecimal saveForApi(long sheetId, long indicatorId, BigDecimal rawValue) {
    if (rawValue == null) {
      throw new BizException(ErrorCode.SCORE_VALUE_INVALID, "原始值不能为空");
    }
    WritePlan plan =
        plan(sheetId, Map.of(indicatorId, rawValue), List.of(), -1L, WriteMode.API);
    if (plan.rows().isEmpty()) {
      throw new BizException(ErrorCode.SCORE_VALUE_INVALID, "原始值不能为空");
    }
    List<Score> rows = plan.rows();
    rows.forEach(
        row -> {
          row.setSource(ScoreSource.API.name());
          row.setImportBatch(null);
        });
    scoreMapper.upsertBatch(rows);
    log.info(
        "开放 API 写入评分：sheetId={} indicatorId={} source=API", sheetId, indicatorId);
    return rows.get(0).getScoreValue();
  }

  /** 一次写入了什么：待 upsert 的行 ＋ 待清空的指标（都已经过守卫与归属校验）。 */
  private record WritePlan(List<Score> rows, List<Long> clearIndicatorIds) {}

  /** 人工路径与开放 API 的守卫差异；换算/归属/upsert 完全共用。 */
  private enum WriteMode {
    USER,
    API
  }

  /**
   * 守卫 + 换算 + 归属校验，产出这次要动的东西。
   *
   * <p>⚠️ **锁定读是本事务的第一条语句**（CLAUDE 默认约定第 6 条）：「状态与负责人」一律取当前值——
   * 用普通读会把"刚被别人提交"的评分单看成仍可写，于是给一张 SUBMITTED 的单子落下新分（04-096）。
   * 只锁 sheet 本行：`submit` 是「先 task_supplier、再 sheet」，本方法不请求 task_supplier，不成环。
   *
   * <p>⚠️ **写入与清空走同一套校验**：清空一个不属于本快照的指标照样 2015。顺序也是刻意的——
   * 先判"指标在不在快照里"再判"值是不是 null"，反过来会让"清空一个不存在的指标"静默变成无事发生。
   */
  private WritePlan plan(
      long sheetId,
      Map<Long, BigDecimal> rawValues,
      List<Long> clearIndicatorIds,
      long operatorId,
      WriteMode mode) {
    Sheet sheet =
        sheetMapper.findByIdForUpdate(sheetId).orElseThrow(() -> new BizException(ErrorCode.SHEET_NOT_FOUND));
    requireWritable(sheet, operatorId, mode);
    SheetContextRow context =
        sheetMapper.findContextById(sheetId).orElseThrow(() -> new BizException(ErrorCode.SHEET_NOT_FOUND));

    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(context.getTemplateVersionId());
    // ⚠️ 归属校验按**这张评分单自己的维度**收，而不是"整个模板版本"：原先写成全版本大集合，
    // 于是给一张"质量"单写进"成本"的指标也能过—— upsert 会落一条本维度根本不展示的幽灵行
    // （01 §6.6 的口径里这张单子没有那个指标）。P2-15 加的删除路径会让同一个洞变成"删掉一条看不见的行"，
    // 所以两条一起收紧（03 §39 登记）。
    Map<Long, IndicatorSnapshot> indicatorsById = new HashMap<>();
    for (var dimension : snapshot.dimensions()) {
      if (dimension.id() != context.getDimensionId().longValue()) {
        continue;
      }
      for (IndicatorSnapshot indicator : dimension.indicators()) {
        indicatorsById.put(indicator.id(), indicator);
      }
    }

    List<Score> rows = new ArrayList<>();
    if (!isEmpty(rawValues)) {
      for (Map.Entry<Long, BigDecimal> entry : rawValues.entrySet()) {
        Long indicatorId = entry.getKey();
        BigDecimal rawValue = entry.getValue();
        IndicatorSnapshot indicator = indicatorsById.get(indicatorId);
        if (indicator == null) {
          // 指标不在这张评分单的维度里 ⇒ 拒绝（不是"忽略这一格"，否则导入会静默漏数据）
          throw new BizException(
              ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND,
              "指标不属于该评分单绑定的维度：indicatorId=" + indicatorId + "，dimensionId=" + context.getDimensionId());
        }
        if (rawValue == null) {
          // 手工路径的"清空"由 controller 拆进 clearIndicatorIds 送来，不会以 null 的形式落到这里；
          // 走到这一支的是 **Excel 导入**：01 §6.6/§6.7 规定"空格视为该格未填"，所以跳过而不是删除
          continue;
        }
        Score score = new Score();
        score.setSheetId(sheetId);
        score.setIndicatorId(indicatorId);
        score.setRawValue(rawValue);
        score.setScoreValue(convert(rawValue, indicator));
        rows.add(score);
      }
    }

    List<Long> clears = new ArrayList<>();
    if (!isEmpty(clearIndicatorIds)) {
      for (Long indicatorId : clearIndicatorIds) {
        if (!indicatorsById.containsKey(indicatorId)) {
          throw new BizException(
              ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND,
              "指标不属于该评分单绑定的维度：indicatorId=" + indicatorId + "，dimensionId=" + context.getDimensionId());
        }
        clears.add(indicatorId);
      }
    }
    return new WritePlan(rows, clears);
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
  private static void requireWritable(Sheet sheet, long operatorId, WriteMode mode) {
    if (mode == WriteMode.API) {
      // 01 I2 的字面口径：任务进行中 + 评分单未提交。UNASSIGNED/SCORING 都未提交；SUBMITTED 拒绝。
      if (sheet.getStatus() != SheetStatus.SCORING && sheet.getStatus() != SheetStatus.UNASSIGNED) {
        throw new BizException(
            ErrorCode.TASK_STATUS_INVALID,
            "开放 API 只能写未提交的评分单，当前状态：" + sheet.getStatus());
      }
      return;
    }
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
