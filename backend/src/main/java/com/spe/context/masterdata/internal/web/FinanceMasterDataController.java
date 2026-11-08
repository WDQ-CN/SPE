package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.AccountMappingView;
import com.spe.context.masterdata.internal.FinanceMasterDataService;
import com.spe.context.masterdata.internal.GlAccountView;
import com.spe.context.masterdata.internal.TaxCodeView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreateAccountMappingRequest;
import com.spe.context.masterdata.internal.web.dto.CreateGlAccountRequest;
import com.spe.context.masterdata.internal.web.dto.CreateTaxCodeRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateAccountMappingRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateGlAccountRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateTaxCodeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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

/** 税码、会计科目与科目映射接口。 */
@RestController
@RequestMapping("/api/masterdata")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class FinanceMasterDataController {

  private final FinanceMasterDataService financeService;

  @GetMapping("/tax-codes")
  public Result<PageResult<TaxCodeView>> listTaxCodes(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(financeService.pageTaxCodes(page, size, keyword, status));
  }

  @PostMapping("/tax-codes")
  public Result<TaxCodeView> createTaxCode(@Valid @RequestBody CreateTaxCodeRequest request) {
    return Result.ok(
        financeService.createTaxCode(
            request.code(), request.country(), request.rate(), request.taxType(), request.effectiveFrom()));
  }

  @PutMapping("/tax-codes/{id}")
  public Result<TaxCodeView> updateTaxCode(
      @PathVariable long id, @Valid @RequestBody UpdateTaxCodeRequest request) {
    return Result.ok(
        financeService.updateTaxCode(
            id,
            request.code(),
            request.country(),
            request.rate(),
            request.taxType(),
            request.effectiveFrom(),
            request.version()));
  }

  @PutMapping("/tax-codes/{id}/status")
  public Result<Void> changeTaxCodeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    financeService.changeTaxCodeStatus(id, request.status(), request.version());
    return Result.ok();
  }

  @GetMapping("/gl-accounts")
  public Result<PageResult<GlAccountView>> listGlAccounts(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Positive(message = "公司参数不合法") Long companyId,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(financeService.pageGlAccounts(page, size, companyId, keyword, status));
  }

  @PostMapping("/gl-accounts")
  public Result<GlAccountView> createGlAccount(@Valid @RequestBody CreateGlAccountRequest request) {
    return Result.ok(
        financeService.createGlAccount(
            request.companyId(),
            request.code(),
            request.name(),
            request.accountType(),
            request.normalBalance(),
            request.requiresCostCenter()));
  }

  @PutMapping("/gl-accounts/{id}")
  public Result<GlAccountView> updateGlAccount(
      @PathVariable long id, @Valid @RequestBody UpdateGlAccountRequest request) {
    return Result.ok(
        financeService.updateGlAccount(
            id,
            request.code(),
            request.name(),
            request.accountType(),
            request.normalBalance(),
            request.requiresCostCenter(),
            request.version()));
  }

  @PutMapping("/gl-accounts/{id}/status")
  public Result<Void> changeGlAccountStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    financeService.changeGlAccountStatus(id, request.status(), request.version());
    return Result.ok();
  }

  @GetMapping("/account-mappings")
  public Result<PageResult<AccountMappingView>> listMappings(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Positive(message = "公司参数不合法") Long companyId,
      @RequestParam(required = false) @Size(max = 64, message = "事件类型长度不得超过 64") String eventType,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(financeService.pageMappings(page, size, companyId, eventType, status));
  }

  @PostMapping("/account-mappings")
  public Result<AccountMappingView> createMapping(
      @Valid @RequestBody CreateAccountMappingRequest request) {
    return Result.ok(
        financeService.createMapping(
            request.companyId(),
            request.eventType(),
            request.dimension(),
            request.debitAccountId(),
            request.creditAccountId(),
            request.taxAccountId(),
            request.validFrom()));
  }

  @PutMapping("/account-mappings/{id}")
  public Result<AccountMappingView> updateMapping(
      @PathVariable long id, @Valid @RequestBody UpdateAccountMappingRequest request) {
    return Result.ok(
        financeService.updateMapping(
            id,
            request.eventType(),
            request.dimension(),
            request.debitAccountId(),
            request.creditAccountId(),
            request.taxAccountId(),
            request.validFrom(),
            request.version()));
  }

  @PutMapping("/account-mappings/{id}/status")
  public Result<Void> changeMappingStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    financeService.changeMappingStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
