package com.spe.context.finance.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.finance.internal.FinanceReportService;
import com.spe.context.finance.internal.JournalEntrySummaryView;
import com.spe.context.finance.internal.JournalEntryView;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会计凭证查询（W5；docs/erp/34 §7、49）。 */
@RestController
@RequestMapping("/api/finance/journal-entries")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class JournalEntryController {

  private final FinanceReportService reportService;

  @GetMapping
  public Result<PageResult<JournalEntrySummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 32, message = "来源类型长度不得超过 32") String sourceType,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return Result.ok(reportService.page(page, size, companyId, sourceType, keyword, from, to));
  }

  @GetMapping("/{id}")
  public Result<JournalEntryView> detail(@PathVariable long id) {
    return Result.ok(reportService.detail(id));
  }
}
