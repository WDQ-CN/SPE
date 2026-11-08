package com.spe.context.reporting.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.spe.context.reporting.internal.ReportDefinition;
import com.spe.context.reporting.internal.ReportRunView;
import com.spe.context.reporting.internal.ReportService;
import com.spe.context.reporting.internal.web.dto.RunReportRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报表中心（docs/erp/39 §1）。 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class ReportController {

  private final ReportService reportService;

  @GetMapping
  public Result<List<ReportDefinition>> definitions() {
    return Result.ok(reportService.definitions());
  }

  @PostMapping("/{code}/runs")
  public Result<ReportRunView> run(
      @PathVariable String code,
      @Valid @RequestBody RunReportRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        reportService.run(code, request.companyId(), request.asOf(), operator.uid()));
  }

  @GetMapping("/runs")
  public Result<PageResult<ReportRunView>> runs(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId) {
    return Result.ok(reportService.pageRuns(page, size, companyId));
  }

  @GetMapping("/runs/{id}")
  public Result<ReportRunView> detail(@PathVariable long id) {
    return Result.ok(reportService.detail(id));
  }

  @GetMapping("/runs/{id}/download")
  public ResponseEntity<byte[]> download(@PathVariable long id) {
    ReportService.Csv csv = reportService.csv(id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(csv.filename()).build().toString())
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(csv.content());
  }
}
