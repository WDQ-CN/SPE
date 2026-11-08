package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.CustomerService;
import com.spe.context.masterdata.internal.CustomerView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreateCustomerRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateCustomerRequest;
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

/** 客户主数据接口。 */
@RestController
@RequestMapping("/api/masterdata/customers")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class CustomerController {

  private final CustomerService customerService;

  @GetMapping
  public Result<PageResult<CustomerView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(customerService.page(page, size, keyword, status));
  }

  @PostMapping
  public Result<CustomerView> create(@Valid @RequestBody CreateCustomerRequest request) {
    return Result.ok(
        customerService.create(
            request.code(),
            request.name(),
            request.taxNo(),
            request.address(),
            request.contactName(),
            request.contactPhone(),
            request.currency(),
            request.paymentTerms(),
            request.creditLimit()));
  }

  @PutMapping("/{id}")
  public Result<CustomerView> update(
      @PathVariable long id, @Valid @RequestBody UpdateCustomerRequest request) {
    return Result.ok(
        customerService.update(
            id,
            request.code(),
            request.name(),
            request.taxNo(),
            request.address(),
            request.contactName(),
            request.contactPhone(),
            request.currency(),
            request.paymentTerms(),
            request.creditLimit(),
            request.version()));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    customerService.changeStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
