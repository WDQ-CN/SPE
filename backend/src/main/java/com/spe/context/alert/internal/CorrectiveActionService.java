package com.spe.context.alert.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.alert.api.events.CorrectiveActionCreatedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 整改任务核心服务（W2；29 §3.2）。
 *
 * <p>所有写路径使用带前置状态与 version 的 UPDATE。`manager` 由 W3 Controller 按角色传入，
 * Service 仍把它作为第三层闸门：EVALUATOR 只能碰本人负责的整改，验证/创建只允许 ADMIN/MANAGER。
 */
@Service
@RequiredArgsConstructor
public class CorrectiveActionService {

  private static final int MEASURE_MAX_LENGTH = 1000;
  private static final int SHORT_TEXT_MAX_LENGTH = 500;

  private final AlertMapper alertMapper;
  private final CorrectiveActionMapper correctiveActionMapper;
  private final CorrectiveActionQueryMapper correctiveActionQueryMapper;
  private final UserQueryFacade userQueryFacade;
  private final AuditFacade auditFacade;
  private final CorrectiveActionVerifier verifier;
  private final EventPublisher eventPublisher;

  @Transactional
  public CorrectiveAction create(
      long alertId,
      long operatorId,
      boolean manager,
      long ownerId,
      LocalDate dueDate,
      String measure,
      String expectedResult) {
    if (!manager) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    Alert alert = alertMapper.selectById(alertId);
    if (alert == null) {
      throw new BizException(ErrorCode.ALERT_NOT_FOUND);
    }
    if (!"UNREAD".equals(alert.getStatus())
        || alert.getBaselineScore() == null
        || alert.getTargetScore() == null
        || correctiveActionMapper.findByAlertId(alertId) != null) {
      throw new BizException(ErrorCode.ALERT_NOT_ACTIONABLE);
    }
    validateOwner(ownerId);
    if (dueDate == null) {
      throw new BizException(ErrorCode.BAD_REQUEST, "截止日期不能为空");
    }
    String normalizedMeasure = requiredText(measure, MEASURE_MAX_LENGTH, "整改措施");
    String normalizedExpected = optionalText(expectedResult, SHORT_TEXT_MAX_LENGTH, "预期结果");

    CorrectiveAction action = new CorrectiveAction();
    action.setAlertId(alertId);
    action.setTaskSupplierId(alert.getTaskSupplierId());
    action.setSupplierId(alert.getSupplierId());
    action.setDimensionId(alert.getDimensionId());
    action.setBaselineYear(alert.getYear());
    action.setBaselineQuarter(alert.getQuarter());
    action.setBaselineScore(alert.getBaselineScore());
    action.setTargetScore(alert.getTargetScore());
    action.setOwnerId(ownerId);
    action.setDueDate(dueDate);
    action.setMeasure(normalizedMeasure);
    action.setExpectedResult(normalizedExpected);
    action.setStatus(CorrectiveActionStatus.OPEN.name());
    action.setCreatedBy(operatorId);
    action.setVersion(0);
    try {
      correctiveActionMapper.insert(action);
    } catch (DuplicateKeyException ex) {
      // 并发下两个请求都通过了 findByAlertId 的前置检查：唯一键 uk_action_alert 是最后一道墙，
      // 这里必须翻成 2040，不能让用户看到 5001。
      throw new BizException(ErrorCode.ALERT_NOT_ACTIONABLE, "该预警已有整改任务");
    }

    // D2：创建整改与预警 HANDLED 同一事务，避免出现“有整改但仍未处理”的双状态。
    int handled = alertMapper.handle(alertId, operatorId, "已创建整改任务 #" + action.getId());
    if (handled == 0) {
      throw new BizException(ErrorCode.ALERT_NOT_ACTIONABLE);
    }

    Map<String, Object> detail = new HashMap<>();
    detail.put("alertId", alertId);
    detail.put("supplierId", alert.getSupplierId());
    detail.put("ownerId", ownerId);
    detail.put("baselineScore", alert.getBaselineScore());
    detail.put("targetScore", alert.getTargetScore());
    auditFacade.record(
        AuditAction.CORRECTIVE_ACTION_CREATE,
        AuditTargetType.CORRECTIVE_ACTION,
        action.getId(),
        detail);
    String supplierName =
        correctiveActionQueryMapper
            .findDetailById(action.getId())
            .map(CorrectiveActionRow::getSupplierName)
            .orElse("供应商 #" + alert.getSupplierId());
    eventPublisher.publish(
        new CorrectiveActionCreatedEvent(
            action.getId(),
            alertId,
            alert.getSupplierId(),
            supplierName,
            ownerId,
            dueDate));
    return action;
  }

  @Transactional
  public CorrectiveAction update(
      long id,
      long operatorId,
      boolean manager,
      long ownerId,
      LocalDate dueDate,
      String measure,
      String expectedResult,
      int version) {
    CorrectiveAction current = load(id);
    ensureOwnerOrManager(current, operatorId, manager);
    ensureOwnerChangeAllowed(current, ownerId, manager);
    ensureVersion(current, version);
    ensureEditable(current);
    validateOwner(ownerId);
    if (dueDate == null) {
      throw new BizException(ErrorCode.BAD_REQUEST, "截止日期不能为空");
    }
    String normalizedMeasure = requiredText(measure, MEASURE_MAX_LENGTH, "整改措施");
    String normalizedExpected = optionalText(expectedResult, SHORT_TEXT_MAX_LENGTH, "预期结果");
    if (correctiveActionMapper.updateEditable(
            id, ownerId, dueDate, normalizedMeasure, normalizedExpected, version)
        == 0) {
      reconcile(current, id, CorrectiveActionStatus.OPEN, CorrectiveActionStatus.IN_PROGRESS,
          CorrectiveActionStatus.REOPENED);
    }
    CorrectiveAction updated = load(id);
    Map<String, Object> detail = new HashMap<>();
    detail.put("ownerId", ownerId);
    detail.put("dueDate", dueDate.toString());
    detail.put("beforeOwnerId", current.getOwnerId());
    detail.put("beforeDueDate", current.getDueDate().toString());
    detail.put("beforeStatus", current.getStatus());
    auditFacade.record(
        AuditAction.CORRECTIVE_ACTION_UPDATE,
        AuditTargetType.CORRECTIVE_ACTION,
        id,
        detail);
    return updated;
  }

  @Transactional
  public CorrectiveAction start(long id, long operatorId, boolean manager, int version) {
    CorrectiveAction current = load(id);
    ensureOwnerOrManager(current, operatorId, manager);
    ensureVersion(current, version);
    CorrectiveActionStatus target = transition(current, CorrectiveActionCommand.START);
    if (correctiveActionMapper.start(id, version) == 0) {
      reconcile(current, id, CorrectiveActionStatus.OPEN, CorrectiveActionStatus.REOPENED);
    }
    auditFacade.record(
        AuditAction.CORRECTIVE_ACTION_START,
        AuditTargetType.CORRECTIVE_ACTION,
        id,
        Map.of("targetStatus", target.name(), "beforeStatus", current.getStatus()));
    return load(id);
  }

  @Transactional
  public CorrectiveAction submit(
      long id, long operatorId, boolean manager, String comment, int version) {
    CorrectiveAction current = load(id);
    ensureOwnerOrManager(current, operatorId, manager);
    ensureVersion(current, version);
    CorrectiveActionStatus target = transition(current, CorrectiveActionCommand.SUBMIT);
    String normalized = optionalText(comment, SHORT_TEXT_MAX_LENGTH, "提交说明");
    if (correctiveActionMapper.submit(id, normalized, version) == 0) {
      reconcile(current, id, CorrectiveActionStatus.IN_PROGRESS);
    }
    auditFacade.record(
        AuditAction.CORRECTIVE_ACTION_SUBMIT,
        AuditTargetType.CORRECTIVE_ACTION,
        id,
        Map.of("targetStatus", target.name(), "beforeStatus", current.getStatus()));
    verifier.fillIfAvailable(id);
    return load(id);
  }

  @Transactional
  public CorrectiveAction verify(
      long id,
      long operatorId,
      boolean manager,
      boolean pass,
      String comment,
      int version) {
    if (!manager) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    CorrectiveAction current = load(id);
    ensureVersion(current, version);
    CorrectiveActionStatus target =
        transition(current, pass ? CorrectiveActionCommand.VERIFY_PASS : CorrectiveActionCommand.VERIFY_FAIL);
    String normalized = optionalText(comment, SHORT_TEXT_MAX_LENGTH, "验证意见");
    if (normalized == null && (current.getVerifyOutcome() == null
        || "PENDING".equals(current.getVerifyOutcome()))) {
      throw new BizException(ErrorCode.BAD_REQUEST, "下期结果待人工判断时，验证意见不能为空");
    }
    if (correctiveActionMapper.verify(id, target.name(), operatorId, normalized, version) == 0) {
      reconcile(current, id, CorrectiveActionStatus.WAITING_VERIFICATION);
    }
    auditFacade.record(
        AuditAction.CORRECTIVE_ACTION_VERIFY,
        AuditTargetType.CORRECTIVE_ACTION,
        id,
        Map.of(
            "targetStatus", target.name(),
            "beforeStatus", current.getStatus(),
            "outcome", Optional.ofNullable(current.getVerifyOutcome()).orElse("PENDING")));
    return load(id);
  }

  @Transactional
  public CorrectiveAction cancel(
      long id, long operatorId, boolean manager, String reason, int version) {
    CorrectiveAction current = load(id);
    if (!manager && current.getCreatedBy() != operatorId) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    ensureVersion(current, version);
    CorrectiveActionStatus target = transition(current, CorrectiveActionCommand.CANCEL);
    String normalized = requiredText(reason, SHORT_TEXT_MAX_LENGTH, "取消原因");
    if (correctiveActionMapper.cancel(id, normalized, version) == 0) {
      reconcile(current, id, CorrectiveActionStatus.OPEN, CorrectiveActionStatus.IN_PROGRESS,
          CorrectiveActionStatus.WAITING_VERIFICATION, CorrectiveActionStatus.REOPENED);
    }
    auditFacade.record(
        AuditAction.CORRECTIVE_ACTION_CANCEL,
        AuditTargetType.CORRECTIVE_ACTION,
        id,
        Map.of(
            "targetStatus", target.name(),
            "beforeStatus", current.getStatus(),
            "reason", normalized));
    return load(id);
  }

  @Transactional(readOnly = true)
  public CorrectiveAction get(long id, long operatorId, boolean manager) {
    CorrectiveAction action = load(id);
    ensureOwnerOrManager(action, operatorId, manager);
    return action;
  }

  /**
   * 整改列表（W3）：ADMIN/MANAGER 看全部；EVALUATOR 强制只看本人。
   *
   * <p>EVALUATOR 显式传别人的 `ownerId` 直接 403，而不是静默改成自己：参数合法但越权，
   * 与详情接口的口径一致（4xxx 而不是返回空列表）。
   */
  @Transactional(readOnly = true)
  public PageResult<CorrectiveActionSummaryView> page(
      PageQuery query,
      String status,
      Long supplierId,
      Long ownerId,
      Boolean overdue,
      long operatorId,
      boolean manager) {
    String normalizedStatus = normalizeStatus(status);
    Long ownerFilter = resolveOwnerFilter(ownerId, operatorId, manager);
    if (supplierId != null && supplierId <= 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "供应商参数不合法");
    }
    LocalDate today = LocalDate.now();
    long total =
        correctiveActionQueryMapper.countPage(
            normalizedStatus, supplierId, ownerFilter, overdue, today);
    if (total == 0) {
      return new PageResult<>(List.of(), 0, query.page(), query.size());
    }
    List<CorrectiveActionSummaryView> rows =
        correctiveActionQueryMapper
            .findPage(
                normalizedStatus,
                supplierId,
                ownerFilter,
                overdue,
                today,
                query.offset(),
                query.size())
            .stream()
            .map(row -> CorrectiveActionSummaryView.of(row, today))
            .toList();
    return new PageResult<>(rows, total, query.page(), query.size());
  }

  @Transactional(readOnly = true)
  public CorrectiveActionDetailView detail(long id, long operatorId, boolean manager) {
    CorrectiveAction action = load(id);
    ensureOwnerOrManager(action, operatorId, manager);
    LocalDate today = LocalDate.now();
    return correctiveActionQueryMapper
        .findDetailById(id)
        .map(row -> CorrectiveActionDetailView.of(row, today))
        .orElseThrow(() -> new BizException(ErrorCode.CORRECTIVE_ACTION_NOT_FOUND));
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String value = status.trim().toUpperCase();
    try {
      CorrectiveActionStatus.valueOf(value);
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "整改状态不合法：" + status);
    }
    return value;
  }

  private static Long resolveOwnerFilter(Long ownerId, long operatorId, boolean manager) {
    if (ownerId != null && ownerId <= 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "负责人参数不合法");
    }
    if (manager) {
      return ownerId;
    }
    if (ownerId != null && ownerId != operatorId) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    return operatorId;
  }

  private CorrectiveAction load(long id) {
    return Optional.ofNullable(correctiveActionMapper.selectById(id))
        .orElseThrow(() -> new BizException(ErrorCode.CORRECTIVE_ACTION_NOT_FOUND));
  }

  private void ensureOwnerOrManager(CorrectiveAction action, long operatorId, boolean manager) {
    if (!manager && action.getOwnerId() != operatorId) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
  }

  /** 改派负责人是 ADMIN/MANAGER 的动作；EVALUATOR 只能改自己任务的其他字段。 */
  private void ensureOwnerChangeAllowed(
      CorrectiveAction action, long ownerId, boolean manager) {
    if (!manager && action.getOwnerId() != ownerId) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
  }

  private void ensureVersion(CorrectiveAction action, int version) {
    if (!action.getVersion().equals(version)) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
  }

  private void ensureEditable(CorrectiveAction action) {
    CorrectiveActionStatus status = valueOf(action.getStatus());
    if (status != CorrectiveActionStatus.OPEN
        && status != CorrectiveActionStatus.IN_PROGRESS
        && status != CorrectiveActionStatus.REOPENED) {
      throw new BizException(ErrorCode.CORRECTIVE_ACTION_STATUS_INVALID);
    }
  }

  private CorrectiveActionStatus transition(
      CorrectiveAction action, CorrectiveActionCommand command) {
    try {
      return CorrectiveActionStateMachine.transition(valueOf(action.getStatus()), command);
    } catch (IllegalStateException ex) {
      throw new BizException(ErrorCode.CORRECTIVE_ACTION_STATUS_INVALID, ex.getMessage());
    }
  }

  private CorrectiveActionStatus valueOf(String status) {
    try {
      return CorrectiveActionStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.CORRECTIVE_ACTION_STATUS_INVALID, "非法整改状态：" + status);
    }
  }

  private void reconcile(
      CorrectiveAction before, long id, CorrectiveActionStatus... allowed) {
    CorrectiveAction latest = correctiveActionMapper.selectById(id);
    if (latest == null) {
      throw new BizException(ErrorCode.CORRECTIVE_ACTION_NOT_FOUND);
    }
    // 版本已变 ⇒ 别人先改了；版本未变但状态不在前置集合 ⇒ 这个动作本身不合法。
    if (!latest.getVersion().equals(before.getVersion())) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    CorrectiveActionStatus status = valueOf(latest.getStatus());
    for (CorrectiveActionStatus candidate : allowed) {
      if (status == candidate) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    }
    throw new BizException(ErrorCode.CORRECTIVE_ACTION_STATUS_INVALID);
  }

  private void validateOwner(long ownerId) {
    UserBrief owner =
        userQueryFacade
            .findById(ownerId)
            .orElseThrow(() -> new BizException(ErrorCode.CORRECTIVE_ACTION_OWNER_INVALID));
    if (!owner.enabled()
        || (owner.role() != Role.EVALUATOR && owner.role() != Role.MANAGER)) {
      throw new BizException(ErrorCode.CORRECTIVE_ACTION_OWNER_INVALID);
    }
  }

  private static String requiredText(String value, int max, String field) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, field + "不能为空");
    }
    if (normalized.length() > max) {
      throw new BizException(ErrorCode.BAD_REQUEST, field + "长度不得超过 " + max + " 字");
    }
    return normalized;
  }

  private static String optionalText(String value, int max, String field) {
    String normalized = value == null ? null : value.trim();
    if (normalized != null && normalized.isEmpty()) {
      return null;
    }
    if (normalized != null && normalized.length() > max) {
      throw new BizException(ErrorCode.BAD_REQUEST, field + "长度不得超过 " + max + " 字");
    }
    return normalized;
  }
}
