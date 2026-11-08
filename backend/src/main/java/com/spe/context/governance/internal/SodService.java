package com.spe.context.governance.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SoD 规则、冲突检查与限时例外（docs/erp/41 §3）。 */
@Service
@RequiredArgsConstructor
public class SodService {

  private final SodRuleMapper ruleMapper;
  private final SodExceptionMapper exceptionMapper;
  private final AuditFacade auditFacade;

  @Transactional
  public SodRule createRule(
      long operatorId, String code, String roleA, String roleB, String description) {
    String normalizedA = normalizeRole(roleA);
    String normalizedB = normalizeRole(roleB);
    if (normalizedA.equals(normalizedB)
        || code == null
        || code.isBlank()
        || description == null
        || description.isBlank()) {
      throw new BizException(ErrorCode.SOD_RULE_INVALID);
    }
    SodRule rule = new SodRule();
    rule.setCode(code.trim().toUpperCase(Locale.ROOT));
    rule.setRoleA(normalizedA);
    rule.setRoleB(normalizedB);
    rule.setDescription(description.trim());
    rule.setStatus(SodRuleStatus.ACTIVE);
    rule.setCreatedBy(operatorId);
    try {
      ruleMapper.insert(rule);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.SOD_RULE_INVALID, "规则编码已存在");
    }
    auditFacade.record(
        AuditAction.SOD_RULE_CREATE,
        AuditTargetType.SOD_RULE,
        rule.getId(),
        Map.of("code", rule.getCode(), "roleA", normalizedA, "roleB", normalizedB));
    return rule;
  }

  @Transactional(readOnly = true)
  public List<SodRule> listRules() {
    return ruleMapper.findAll();
  }

  @Transactional(readOnly = true)
  public List<SodConflictView> check(Set<String> roles, Long userId, LocalDate date) {
    LocalDate effectiveDate = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
    Set<String> normalized =
        roles == null
            ? Set.of()
            : roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    return ruleMapper.findActive().stream()
        .filter(rule -> normalized.contains(rule.getRoleA()) && normalized.contains(rule.getRoleB()))
        .map(
            rule -> {
              boolean excepted =
                  userId != null
                      && exceptionMapper.countApproved(userId, rule.getId(), effectiveDate) > 0;
              return new SodConflictView(
                  rule.getId(),
                  rule.getCode(),
                  rule.getRoleA(),
                  rule.getRoleB(),
                  rule.getDescription(),
                  excepted);
            })
        .toList();
  }

  @Transactional
  public SodException requestException(
      long operatorId,
      long userId,
      long ruleId,
      String reason,
      LocalDate validFrom,
      LocalDate validTo) {
    if (userId == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "不能为本人申请 SoD 例外");
    }
    ruleMapper
        .findById(ruleId)
        .orElseThrow(() -> new BizException(ErrorCode.SOD_RULE_NOT_FOUND));
    if (reason == null || reason.isBlank() || validFrom == null || validTo == null
        || validTo.isBefore(validFrom)) {
      throw new BizException(ErrorCode.SOD_RULE_INVALID, "例外原因/有效期不合法");
    }
    SodException exception = new SodException();
    exception.setUserId(userId);
    exception.setRuleId(ruleId);
    exception.setReason(reason.trim());
    exception.setValidFrom(validFrom);
    exception.setValidTo(validTo);
    exception.setStatus(SodExceptionStatus.PENDING);
    exception.setRequestedBy(operatorId);
    exception.setVersion(0);
    exceptionMapper.insert(exception);
    auditFacade.record(
        AuditAction.SOD_EXCEPTION_REQUEST,
        AuditTargetType.SOD_EXCEPTION,
        exception.getId(),
        Map.of("userId", userId, "ruleId", ruleId, "validTo", validTo));
    return exception;
  }

  @Transactional
  public SodException review(long id, long operatorId, boolean approve) {
    SodException exception =
        exceptionMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.SOD_EXCEPTION_NOT_FOUND));
    if (exception.getStatus() != SodExceptionStatus.PENDING) {
      throw new BizException(ErrorCode.SOD_EXCEPTION_STATUS_INVALID);
    }
    if (exception.getRequestedBy() == operatorId || exception.getUserId() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "申请人/受益人不能审批该例外");
    }
    SodExceptionStatus target =
        approve ? SodExceptionStatus.APPROVED : SodExceptionStatus.REJECTED;
    if (exceptionMapper.markReviewed(
            id, target, operatorId, LocalDateTime.now(ZoneOffset.UTC))
        == 0) {
      throw new BizException(ErrorCode.SOD_EXCEPTION_STATUS_INVALID);
    }
    if (approve) {
      auditFacade.record(
          AuditAction.SOD_EXCEPTION_APPROVE,
          AuditTargetType.SOD_EXCEPTION,
          id,
          Map.of("userId", exception.getUserId(), "ruleId", exception.getRuleId()));
    }
    exception.setStatus(target);
    return exception;
  }

  @Transactional(readOnly = true)
  public PageResult<SodException> pageExceptions(int page, int size) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        exceptionMapper.findPage(query.offset(), query.size()),
        exceptionMapper.countAll(),
        query.page(),
        query.size());
  }

  private static String normalizeRole(String role) {
    if (role == null || role.isBlank() || role.trim().length() > 32) {
      throw new BizException(ErrorCode.SOD_RULE_INVALID, "角色不能为空且不得超过 32");
    }
    return role.trim().toUpperCase(Locale.ROOT);
  }
}
