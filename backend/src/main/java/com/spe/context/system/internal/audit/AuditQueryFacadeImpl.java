package com.spe.context.system.internal.audit;

import com.spe.context.system.api.AuditQueryFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.AuditTrailEntry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 审计只读门面实现（W4）：只读 `audit_log`，按目标取轨迹。 */
@Component
@RequiredArgsConstructor
public class AuditQueryFacadeImpl implements AuditQueryFacade {

  private final AuditLogReadMapper readMapper;

  @Override
  @Transactional(readOnly = true)
  public List<AuditTrailEntry> listForTarget(
      AuditTargetType targetType, long targetId, int limit) {
    int safeLimit = Math.min(Math.max(limit, 1), 100);
    return readMapper.findForTarget(targetType.name(), targetId, safeLimit).stream()
        .map(
            row ->
                new AuditTrailEntry(
                    row.getId(),
                    row.getAction(),
                    row.getOperatorId(),
                    row.getOperatorName(),
                    row.getDetail(),
                    row.getCreatedAt()))
        .toList();
  }
}
