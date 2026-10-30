package com.spe.context.system.internal.audit;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditTargetType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作日志查询（01 SM5；M5 W5）。
 *
 * <p>时间过滤的时区语义（22 §2 D8）：请求是 ISO-8601 instant，服务端折算成 **UTC LocalDateTime** 再与
 * `created_at`（库内 UTC）比较；前端负责把本地日期边界转成 instant。`from >= to` 直接 400。
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

  private final AuditLogReadMapper readMapper;

  @Transactional(readOnly = true)
  public PageResult<AuditLogView> page(
      PageQuery query,
      String action,
      String targetType,
      String operator,
      OffsetDateTime from,
      OffsetDateTime to) {
    String actionFilter = validateAction(action);
    String targetFilter = validateTargetType(targetType);
    String operatorFilter =
        operator == null || operator.isBlank() ? null : SqlKeywords.escapeLike(operator.trim());
    LocalDateTime fromUtc = toUtc(from);
    LocalDateTime toUtc = toUtc(to);
    if (fromUtc != null && toUtc != null && !fromUtc.isBefore(toUtc)) {
      throw new BizException(ErrorCode.BAD_REQUEST, "时间区间的开始必须早于结束");
    }
    long total = readMapper.count(actionFilter, targetFilter, operatorFilter, fromUtc, toUtc);
    if (total == 0) {
      return new PageResult<>(List.of(), 0, query.page(), query.size());
    }
    List<AuditLogView> rows =
        readMapper
            .findPage(actionFilter, targetFilter, operatorFilter, fromUtc, toUtc, query.offset(), query.size())
            .stream()
            .map(AuditLogView::of)
            .toList();
    return new PageResult<>(rows, total, query.page(), query.size());
  }

  @Transactional(readOnly = true)
  public List<String> actions() {
    return readMapper.findActions();
  }

  private static String validateAction(String action) {
    if (action == null || action.isBlank()) {
      return null;
    }
    try {
      return AuditAction.valueOf(action.trim()).name();
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "审计动作码不合法：" + action);
    }
  }

  private static String validateTargetType(String targetType) {
    if (targetType == null || targetType.isBlank()) {
      return null;
    }
    try {
      return AuditTargetType.valueOf(targetType.trim()).name();
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "审计目标类型不合法：" + targetType);
    }
  }

  private static LocalDateTime toUtc(OffsetDateTime value) {
    return value == null
        ? null
        : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
  }
}
