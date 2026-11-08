package com.spe.context.governance.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;

/** 治理请求体集合。 */
public final class GovernanceRequests {

  private GovernanceRequests() {}

  public record CreateSodRule(
      @NotBlank(message = "规则编码不能为空") @Size(max = 64) String code,
      @NotBlank(message = "角色 A 不能为空") @Size(max = 32) String roleA,
      @NotBlank(message = "角色 B 不能为空") @Size(max = 32) String roleB,
      @NotBlank(message = "说明不能为空") @Size(max = 255) String description) {}

  public record CheckSod(
      @NotEmpty(message = "至少提供一个角色") Set<@NotBlank @Size(max = 32) String> roles,
      Long userId,
      LocalDate date) {}

  public record RequestSodException(
      @NotNull(message = "用户不能为空") Long userId,
      @NotNull(message = "规则不能为空") Long ruleId,
      @NotBlank(message = "原因不能为空") @Size(max = 500) String reason,
      @NotNull(message = "生效日不能为空") LocalDate validFrom,
      @NotNull(message = "失效日不能为空") LocalDate validTo) {}
}
