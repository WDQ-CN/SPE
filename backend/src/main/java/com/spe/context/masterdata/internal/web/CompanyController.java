package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.CompanyView;
import com.spe.context.masterdata.internal.OrganizationService;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreateCompanyRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateCompanyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公司主数据接口（docs/erp/31、49、52）。 */
@RestController
@RequestMapping("/api/masterdata/companies")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class CompanyController {

  private final OrganizationService organizationService;

  @GetMapping
  public Result<PageResult<CompanyView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(organizationService.pageCompanies(page, size, keyword, status));
  }

  @PostMapping
  public Result<CompanyView> create(@Valid @RequestBody CreateCompanyRequest request) {
    return Result.ok(
        organizationService.createCompany(
            request.code(), request.name(), request.baseCurrency(), request.taxNo(), request.country()));
  }

  @PutMapping("/{id}")
  public Result<CompanyView> update(
      @PathVariable long id, @Valid @RequestBody UpdateCompanyRequest request) {
    return Result.ok(
        organizationService.updateCompany(
            id,
            request.code(),
            request.name(),
            request.baseCurrency(),
            request.taxNo(),
            request.country(),
            request.version()));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    organizationService.changeCompanyStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
