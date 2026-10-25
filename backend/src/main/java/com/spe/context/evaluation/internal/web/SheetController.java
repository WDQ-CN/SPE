package com.spe.context.evaluation.internal.web;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.scoring.ScoreWriter;
import com.spe.context.evaluation.internal.scoring.ScoringService;
import com.spe.context.evaluation.internal.scoring.SheetPreview;
import com.spe.context.evaluation.internal.scoring.SheetScoreView;
import com.spe.context.evaluation.internal.scoring.SubmitResult;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.context.evaluation.internal.web.dto.AssignSheetRequest;
import com.spe.context.evaluation.internal.web.dto.PreviewScoresRequest;
import com.spe.context.evaluation.internal.web.dto.SaveScoresRequest;
import com.spe.context.evaluation.internal.web.dto.SubmitSheetRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评分单接口（02 §4 / 13 §E13、§E18）。
 *
 * <p><b>两层权限</b>：类级注解保证"只有 EVALUATOR/MANAGER 能进来"，"这张评分单是不是指派给我的"
 * 由 Service 内的第二层过滤判定（越权 4002）——两层都不能省（13 §E13）。
 *
 * <p>⚠️ `operator.uid()` **直接取自令牌**（`JwtAuthenticationFilter` 写入的 principal），取 id 这一步不必
 * 回查用户表；但**过滤器本身每个请求都会按 uid 回查账号状态与角色**（SEC-1：认证依赖的事实要以库里
 * 当前值为准）——别把那次回查当成多余而删掉（03 §34 的 P2-14）。
 */
@RestController
@RequestMapping("/api/sheets")
@PreAuthorize("hasAnyRole('MANAGER','EVALUATOR')")
@RequiredArgsConstructor
public class SheetController {

  private final ScoringService scoringService;
  private final TaskService taskService;

  /** 改派（01 J2）：仅 MANAGER；只允许「待指派 / 评分中」，不换单不清数据。 */
  @PutMapping("/{id}/assign")
  @PreAuthorize("hasRole('MANAGER')")
  public Result<Void> assign(
      @PathVariable long id, @Valid @RequestBody AssignSheetRequest request) {
    taskService.reassign(id, request.assigneeId(), request.version());
    return Result.ok();
  }

  /** 读取评分单（打分页初始化）：指标清单 + 已填值 + 档位文案。 */
  @GetMapping("/{id}/scores")
  public Result<SheetScoreView> readScores(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(scoringService.readSheet(id, operator.uid()));
  }

  /**
   * 暂存（01 E2）：只传本次改动的格子，写入走 `ScoreWriter`。
   *
   * <p>请求里的 null 在这里**拆成两路**（P2-15）：有值的走 upsert，显式 null 的走清空。
   * 不在 DTO 里保留 null 语义往下传，是因为同一个 `Map<Long, BigDecimal>` 还被试算与导入用着，
   * 而那里的 null 是"未填"（01 §6.6），不是"删除"。
   */
  @PutMapping("/{id}/scores")
  public Result<Integer> saveScores(
      @PathVariable long id,
      @Valid @RequestBody SaveScoresRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    Map<Long, BigDecimal> values = new LinkedHashMap<>();
    List<Long> clearIndicatorIds = new ArrayList<>();
    request
        .scores()
        .forEach(
            entry -> {
              if (entry.rawValue() == null) {
                clearIndicatorIds.add(entry.indicatorId());
              } else {
                values.put(entry.indicatorId(), entry.rawValue());
              }
            });
    return Result.ok(scoringService.saveScores(id, operator.uid(), values, clearIndicatorIds));
  }

  /** 试算（缺口端点）：**不落库**，只算；分值与等级都可能为 null（E6 的不完整口径）。 */
  @PostMapping("/{id}/preview")
  public Result<SheetPreview> preview(
      @PathVariable long id,
      @Valid @RequestBody PreviewScoresRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    Map<Long, BigDecimal> candidates = new LinkedHashMap<>();
    request.scores().forEach(entry -> candidates.put(entry.indicatorId(), entry.rawValue()));
    return Result.ok(scoringService.preview(id, operator.uid(), candidates));
  }

  /** 提交（T2）：该维度全部有值才可提交；提交后锁定。 */
  @PostMapping("/{id}/submit")
  public Result<SubmitResult> submit(
      @PathVariable long id,
      @Valid @RequestBody SubmitSheetRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(scoringService.submit(id, operator.uid(), request.version()));
  }
}