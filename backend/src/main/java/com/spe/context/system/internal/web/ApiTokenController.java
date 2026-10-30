package com.spe.context.system.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.system.internal.token.ApiTokenCreated;
import com.spe.context.system.internal.token.ApiTokenService;
import com.spe.context.system.internal.token.ApiTokenSummary;
import com.spe.context.system.internal.web.dto.CreateApiTokenRequest;
import com.spe.context.system.internal.web.dto.DisableApiTokenRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API Token 管理（M6 / 01 I3；02 §4）。
 *
 * <p>仅 ADMIN；列表永不返回明文/哈希；生成响应加 `Cache-Control: no-store`（明文只出现一次）。
 */
@RestController
@RequestMapping("/api/api-tokens")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ApiTokenController {

  private final ApiTokenService apiTokenService;

  @GetMapping
  public Result<PageResult<ApiTokenSummary>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
    return Result.ok(apiTokenService.page(PageQuery.of(page, size)));
  }

  @PostMapping
  public ResponseEntity<Result<ApiTokenCreated>> create(
      @Valid @RequestBody CreateApiTokenRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    ApiTokenCreated created = apiTokenService.create(request.name(), operator.uid());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Result.ok(created));
  }

  @PutMapping("/{id}/disable")
  public Result<Void> disable(
      @PathVariable long id, @Valid @RequestBody DisableApiTokenRequest request) {
    apiTokenService.disable(id, request.version());
    return Result.ok();
  }
}
