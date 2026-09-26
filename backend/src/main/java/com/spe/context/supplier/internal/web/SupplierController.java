package com.spe.context.supplier.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.supplier.internal.SupplierService;
import com.spe.context.supplier.internal.SupplierSummary;
import com.spe.context.supplier.internal.web.dto.ChangeSupplierStatusRequest;
import com.spe.context.supplier.internal.web.dto.CreateSupplierRequest;
import com.spe.context.supplier.internal.web.dto.UpdateSupplierRequest;
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

/**
 * 供应商档案接口（02 §4 / 01 S1、S2）。
 *
 * <p>鉴权锚定 01 §7：供应商维护是 **ADMIN/MANAGER**（EVALUATOR/VIEWER 无入口）。类级
 * `@PreAuthorize` 是「默认拒绝」的落地，新增方法自动落在同一权限边界内。越权由后端 4xxx 兜底并在
 * system 上下文留 `ACCESS_DENIED`，不依赖前端隐藏入口。
 *
 * <p>P3 打分页需要显示供应商名时，数据由 evaluation 后端经 `SupplierQueryFacade` 组装，
 * **不因此放松本端点的角色注解**（03 §14 R9-21）。
 *
 * <p>Controller 只做参数校验、调 Service、包 `Result`；审计由 Service 调用——Controller 若直接注入
 * `AuditFacade` 会跨上下文而触 R-E 红灯（06 §2.7）。
 */
@RestController
@RequestMapping("/api/suppliers")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SupplierController {

  private final SupplierService supplierService;

  /**
   * 列表：`page` 从 1 起（09 D8），`keyword` 命中编码或名称、`category` 命中分类，均可选、可组合。
   * 查询参数的长度上限与列长度对齐，由类级 `@Validated` 触发校验（`GlobalExceptionHandler` 映射为 2001）。
   */
  @GetMapping
  public Result<PageResult<SupplierSummary>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 50, message = "分类长度不得超过 50") String category) {
    return Result.ok(supplierService.page(page, size, keyword, category));
  }

  @PostMapping
  public Result<SupplierSummary> create(@Valid @RequestBody CreateSupplierRequest request) {
    return Result.ok(supplierService.create(request.code(), request.name(), request.category()));
  }

  /** 编辑（编码可改，E17）：`version` 不符 → 3001；目标不存在 → 2008。 */
  @PutMapping("/{id}")
  public Result<SupplierSummary> update(
      @PathVariable long id, @Valid @RequestBody UpdateSupplierRequest request) {
    return Result.ok(
        supplierService.update(id, request.code(), request.name(), request.category(), request.version()));
  }

  /** 启停（01 S2）：守卫 UPDATE，0 行（版本不符或已处于目标状态）统一 → 3001。 */
  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeSupplierStatusRequest request) {
    supplierService.changeStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
