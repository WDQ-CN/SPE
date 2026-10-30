package com.spe.context.system.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.system.internal.audit.AuditLogService;
import com.spe.context.system.internal.audit.AuditLogView;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询页（01 SM5；M5 W5，02 §4）。
 *
 * <p>仅 ADMIN；`from/to` 是 ISO-8601 instant（前端把本地日期边界转换后传），
 * 服务端折算 UTC 后与库内时间比较；`detail` 原样 JSON 字符串。
 */
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AuditLogController {

  private final AuditLogService auditLogService;

  @GetMapping
  public Result<PageResult<AuditLogView>> page(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String operator,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    return Result.ok(
        auditLogService.page(
            PageQuery.of(page, size), action, targetType, operator, from, to));
  }

  @GetMapping("/actions")
  public Result<List<String>> actions() {
    return Result.ok(auditLogService.actions());
  }
}
