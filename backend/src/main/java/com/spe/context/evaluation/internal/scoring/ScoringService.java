package com.spe.context.evaluation.internal.scoring;

import com.spe.common.event.EventPublisher;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.ScoreSource;
import com.spe.context.evaluation.api.SheetStatus;
import com.spe.context.evaluation.api.events.ApprovalRoundStartedEvent;
import com.spe.context.evaluation.internal.scoring.calculator.IndicatorScore;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreCalculator;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreSheetResult;
import com.spe.context.evaluation.internal.scoring.calculator.TaskSupplierScore;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetContextRow;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.evaluation.internal.task.ApprovalNodeMapper;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.GradeThresholds;
import com.spe.context.system.api.SettingsFacade;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评分域服务（01 E1~E5；05 §5.5 T2；13 §E7/E8/E9/E18）：读打分页、试算、暂存、提交。
 *
 * <p>两条不变量：
 * <ul>
 *   <li><b>写只有一个入口</b>：暂存/导入都经 {@link ScoreWriter}（13 §E7），本类不直接碰 {@code ScoreMapper}；
 *   <li><b>算只有一个实现</b>：试算与提交都调 {@link ScoreCalculator#calculateTaskSupplier} /
 *       {@link ScoreCalculator#calculateSheet}（ADR-4 的"一处实现三处复用"）——试算另写一份就是旁路实现。
 * </ul>
 *
 * <p>可见性一律先过 {@link TaskService#requireReadableSheet}（第二层过滤，13 §E13）：越权给 4002，
 * 而不是"返回空列表"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

  private final ScoreMapper scoreMapper;
  private final SheetMapper sheetMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final ApprovalNodeMapper approvalNodeMapper;
  private final ScoreWriter scoreWriter;
  private final TaskService taskService;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SettingsFacade settingsFacade;
  private final AuditFacade auditFacade;
  private final EventPublisher eventPublisher;

  /** 打分页初始化（01 E2）：指标清单 + 已填值 + 档位文案。 */
  @Transactional(readOnly = true)
  public SheetScoreView readSheet(long sheetId, long operatorId) {
    taskService.requireReadableSheet(sheetId, operatorId);
    SheetContextRow context = requireContext(sheetId);
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(context.getTemplateVersionId());
    DimensionSnapshot dimension = dimensionOf(snapshot, context.getDimensionId());
    Map<Long, Score> saved = scoresByIndicator(sheetId);

    List<SheetScoreView.IndicatorEntry> entries = new ArrayList<>(dimension.indicators().size());
    for (IndicatorSnapshot indicator : dimension.indicators()) {
      Score score = saved.get(indicator.id());
      entries.add(
          new SheetScoreView.IndicatorEntry(
              indicator.id(),
              indicator.code(),
              indicator.name(),
              indicator.unit(),
              indicator.type(),
              indicator.scoreMode(),
              indicator.weight(),
              indicator.targetValue(),
              indicator.standard(),
              score == null ? null : score.getRawValue(),
              score == null ? null : score.getScoreValue()));
    }
    return new SheetScoreView(
        sheetId,
        context.getTaskId(),
        context.getTaskSupplierId(),
        dimension.id(),
        dimension.name(),
        context.getSheetStatus(),
        context.getSheetVersion() == null ? 0 : context.getSheetVersion(),
        context.getAssigneeId(),
        entries);
  }

  /**
   * 试算（01 E2/E5、13 §E18）：把"候选值"叠加到已保存值之上，算**整个评估单**，**不落库**。
   *
   * <p>候选值只覆盖目标评分单那一维度；其它维度用各自已保存的值——这样打分页看到的"总分"才是
   * 当前真实的试算，而不是"其它维度当作没填"。
   */
  @Transactional(readOnly = true)
  public SheetPreview preview(long sheetId, long operatorId, Map<Long, BigDecimal> candidateValues) {
    taskService.requireReadableSheet(sheetId, operatorId);
    SheetContextRow context = requireContext(sheetId);
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(context.getTemplateVersionId());

    // 两条查询搞定（不是 1 + N）：先取兄弟评分单，再**一次**取回它们的全部评分记录。
    // 原先逐张 findBySheetId（N = 维度数），而 `findBySheetIds` 就在同一个 Mapper 里现成可用（03 §34 的 P2-7）。
    List<Sheet> siblings = sheetMapper.findByTaskSupplierId(context.getTaskSupplierId());
    List<Score> scores =
        siblings.isEmpty()
            ? List.of()
            : scoreMapper.findBySheetIds(siblings.stream().map(Sheet::getId).toList());
    // 与定稿/报告共用同一组装器（03 §51 P2-9）：映射只有一份
    Map<Long, Map<Long, BigDecimal>> valuesByDimension =
        ScoreValueAssembler.byDimension(siblings, scores);
    if (candidateValues != null && !candidateValues.isEmpty()) {
      Map<Long, BigDecimal> target =
          valuesByDimension.computeIfAbsent(context.getDimensionId(), key -> new LinkedHashMap<>());
      candidateValues.forEach(
          (indicatorId, rawValue) -> {
            if (rawValue == null) {
              target.remove(indicatorId);
            } else {
              target.put(indicatorId, rawValue);
            }
          });
    }
    TaskSupplierScore result =
        ScoreCalculator.calculateTaskSupplier(snapshot, valuesByDimension, thresholds());
    return new SheetPreview(sheetId, context.getDimensionId(), result);
  }

  /**
   * 暂存（01 E2）：走唯一写入口；审计不在这里写——13 §E20 的词表里"暂存"没有动作码，只有提交/导入有。
   *
   * @param clearIndicatorIds 要**清空**的格子（P2-15）。由 Controller 从请求里 `rawValue == null` 的那些拆出来，
   *     所以这里的两条语义是分开的：**没在请求里** = 不动，**显式 null** = 删掉。
   */
  @Transactional
  public int saveScores(
      long sheetId, long operatorId, Map<Long, BigDecimal> values, List<Long> clearIndicatorIds) {
    // 可见性 + 状态 + 当前读都在**写入口内**判（04-096 的单入口守卫）：
    // 原先这里先调一次 requireReadableSheet，而 Excel 导入那条路径没有——同一条不变量漏了一处。
    return scoreWriter.save(sheetId, values, clearIndicatorIds, ScoreSource.MANUAL, null, operatorId);
  }

  /**
   * 提交（T2；13 §E9 的六步，顺序固定）。
   *
   * <p>第 5 步的 0 行**必须抛 3001**：静默放过会留下"评估单已进待审批、而本张评分单的提交被吞掉"的错觉
   * （13 §E9 的原话），而且这里抛错会让**整个事务回滚**——失败的评分单也不会落 SUBMITTED。
   */
  @Transactional
  public SubmitResult submit(long sheetId, long operatorId, int version) {
    SheetContextRow context = requireContext(sheetId);
    // 1) **先锁评估单行**：同一评估单的提交完全串行化。顺序不能反——先锁自己的评分单行、再去读兄弟行
    //    会形成 X(自己) 等 S(兄弟) ↔ X(兄弟) 等 S(自己) 的**死锁**（本地实测 DeadlockLoserDataAccessException，
    //    与 04-076 同族）。锁住它之后，后面读到的评估单版本就是权威值。
    TaskSupplier taskSupplier =
        taskSupplierMapper
            .lockById(context.getTaskSupplierId())
            .orElseThrow(
                () ->
                    new BizException(
                        ErrorCode.SHEET_NOT_FOUND,
                        "评分单所属的评估单不存在：taskSupplierId=" + context.getTaskSupplierId()));
    // 2) 再当前读自己的评分单行（状态/负责人/版本都取最新）
    Sheet sheet = sheetMapper.findByIdForUpdate(sheetId).orElseThrow(ScoringService::sheetNotFound);
    if (sheet.getAssigneeId() == null || sheet.getAssigneeId() != operatorId) {
      throw new BizException(ErrorCode.SHEET_NOT_ACCESSIBLE);
    }
    if (sheet.getStatus() != SheetStatus.SCORING) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID, "只有「评分中」的评分单可以提交，当前状态：" + sheet.getStatus());
    }
    if (sheet.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    // 2) 该维度必须全部有值（缺哪个就把指标编码列进 message，01 E4 / 判据 4）
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(context.getTemplateVersionId());
    DimensionSnapshot dimension = dimensionOf(snapshot, context.getDimensionId());
    // ⚠️ 这一读必须是**当前读**（03 §38 的 P1-1）：:170 的映射读已经把本事务的 read view 钉在取锁之前，
    //    用普通读会把"刚被别人导入/暂存的分值"看成旧值 ⇒ 可能以从未被校验过的内容定稿（04-057 同根因）。
    //    锁模式取 X 而不是 S：本方法后面可能 correctValues 写这些行（04-076 为 S→X 升级付过代价）。
    List<Score> savedRows = scoreMapper.findValuesForUpdate(sheetId);
    Map<Long, BigDecimal> savedValues = rawValuesOf(savedRows);
    ScoreSheetResult result = ScoreCalculator.calculateSheet(dimension, savedValues, thresholds());
    if (!result.complete()) {
      throw new BizException(
          ErrorCode.SHEET_SCORES_INCOMPLETE,
          "该维度还有指标未填写：" + String.join("、", result.missingCodes()));
    }
    // 3) E8 的第二道守卫：提交时重算，与库里的 score_value 比对（正常恒等；不等说明有人绕过快照）
    int corrected = rewriteIfStale(sheetId, savedValues, result, savedRows, operatorId);

    // 4) 评分单守卫迁移
    if (sheetMapper.markSubmitted(sheetId, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    // 5) 别的维度都提交了吗？——这一读必须是当前读（13 §E11），且**排除自己那一行**
    //    （自己已在本事务里被置为 SUBMITTED，把它算进"未提交"会永远迁不动；把它算进 S 锁集合又会死锁）
    List<Long> unsubmitted =
        sheetMapper.lockOtherUnsubmittedIds(context.getTaskSupplierId(), sheetId);
    String taskSupplierStatus = "SCORING";
    if (unsubmitted.isEmpty()) {
      if (taskSupplierMapper.markPendingApproval(taskSupplier.getId(), taskSupplier.getVersion()) == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      taskSupplierStatus = "PENDING_APPROVAL";
      // 6) 发事件：P3 有发布方、消费方在 P4（13 §E9 说明为什么这不是"声明了没人消费"）。
      //    round 取 :174 已锁到的那一行（当前读；驳回时 T5 在同一条守卫 UPDATE 里 +1），**不写死 1**；
      //    查不到第一级审批人**抛错、不发事件**——CLAUDE 默认约定第 8 条："查不到不许兜底值"，
      //    兜成 uid=0 会让 W2 的监听器写出一行没人能收到的通知（03 §38 的 P1-3）。
      Long approverId = approvalNodeMapper.findFirstApproverId(context.getTaskId());
      if (approverId == null) {
        log.error(
            "任务缺少 seq=1 的审批节点，无法发起审批轮次：taskId={} taskSupplierId={} round={}",
            context.getTaskId(),
            context.getTaskSupplierId(),
            taskSupplier.getRound());
        throw new BizException(
            ErrorCode.USER_NOT_ELIGIBLE,
            "任务的第一级审批节点不存在，请联系采购经理修正审批链：taskId=" + context.getTaskId());
      }
      eventPublisher.publish(
          new ApprovalRoundStartedEvent(
              context.getTaskSupplierId(), approverId, taskSupplier.getRound()));
    }
    auditFacade.record(
        AuditAction.SHEET_SUBMIT,
        AuditTargetType.TASK_SUPPLIER,
        context.getTaskSupplierId(),
        Map.of(
            "sheetId", sheetId,
            "indicators", savedValues.size(),
            "taskSupplierStatus", taskSupplierStatus));

    return new SubmitResult(
        sheetId, context.getTaskSupplierId(), taskSupplierStatus, savedValues.size(), corrected);
  }

  // ---------- 内部 ----------

  private GradeThresholds thresholds() {
    return settingsFacade.gradeThresholds();
  }

  private SheetContextRow requireContext(long sheetId) {
    return sheetMapper.findContextById(sheetId).orElseThrow(ScoringService::sheetNotFound);
  }

  private Map<Long, Score> scoresByIndicator(long sheetId) {
    Map<Long, Score> byIndicator = new LinkedHashMap<>();
    for (Score score : scoreMapper.findBySheetId(sheetId)) {
      byIndicator.put(score.getIndicatorId(), score);
    }
    return byIndicator;
  }

  private Map<Long, BigDecimal> rawValuesOf(List<Score> rows) {
    Map<Long, BigDecimal> values = new LinkedHashMap<>();
    for (Score score : rows) {
      if (score.getRawValue() != null) {
        values.put(score.getIndicatorId(), score.getRawValue());
      }
    }
    return values;
  }

  /**
   * 重算并与库里比对（13 §E8）：只在不一致时才回写（正常路径零写）。
   *
   * <p>为什么"回写"而不是"报错"：任务的模板版本是**已发布**的，快照永不变化 ⇒ 重算必然相等；
   * 不等只可能来自"历史脏行"或有人绕过 ScoreWriter 写库。这时**让权威值落库**比拦住用户更安全，
   * 但要留日志（WARN）与审计里的计数，便于事后追查。
   */
  private int rewriteIfStale(
      long sheetId,
      Map<Long, BigDecimal> savedValues,
      ScoreSheetResult recomputed,
      List<Score> savedRows,
      long operatorId) {
    // 复用提交前那次当前读的结果：再查一次不但多一条 SQL，还会重新落到同一个 read view 上
    Map<Long, BigDecimal> stored = new LinkedHashMap<>();
    for (Score score : savedRows) {
      stored.put(score.getIndicatorId(), score.getScoreValue());
    }
    int stale = 0;
    for (IndicatorScore indicatorScore : recomputed.indicators()) {
      BigDecimal before = stored.get(indicatorScore.indicatorId());
      if (before == null || before.compareTo(indicatorScore.scoreValue()) != 0) {
        stale++;
      }
    }
    if (stale > 0) {
      log.warn("提交时重算与库里的 score_value 不一致，已按快照回写：sheetId={}，不一致 {} 格", sheetId, stale);
      scoreWriter.correctValues(sheetId, savedValues, operatorId);
    }
    return stale;
  }

  private static DimensionSnapshot dimensionOf(TemplateSnapshot snapshot, long dimensionId) {
    return snapshot.dimensions().stream()
        .filter(dimension -> dimension.id() == dimensionId)
        .findFirst()
        .orElseThrow(
            () ->
                new BizException(
                    ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND,
                    "评分单的维度不在任务的模板版本里：dimensionId=" + dimensionId));
  }

  private static BizException sheetNotFound() {
    return new BizException(ErrorCode.SHEET_NOT_FOUND);
  }
}
