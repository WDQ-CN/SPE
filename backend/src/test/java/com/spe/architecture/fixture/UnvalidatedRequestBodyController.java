package com.spe.architecture.fixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 故意**用 `@RequestBody` 但不带 `@Valid`** 的 Controller 夹具：用于证明入参校验那条规则真的会失败
 * （见 {@code ArchUnitRulesAreEffectiveTest}）。
 *
 * <p>已经带上类级 `@PreAuthorize`，所以只违反「入参必须校验」这一条，不牵连默认拒绝那条规则。
 */
@PreAuthorize("hasRole('ADMIN')")
public class UnvalidatedRequestBodyController {

  public String create(@RequestBody String payload) {
    return payload;
  }
}
