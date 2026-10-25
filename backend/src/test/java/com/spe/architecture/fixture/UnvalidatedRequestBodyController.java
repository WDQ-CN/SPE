package com.spe.architecture.fixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故意**用 `@RequestBody` 但不带 `@Valid`** 的 Controller 夹具：用于证明入参校验那条规则真的会失败
 * （见 {@code ArchUnitRulesAreEffectiveTest}）。
 *
 * <p>已经带上类级 `@PreAuthorize`，所以只违反「入参必须校验」这一条，不牵连默认拒绝那条规则。
 * ⚠️ 2026-10-17（P2-12）：两条 R-H 规则都改成按 `@RestController` 注解选取 ⇒ 夹具必须带上它。
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class UnvalidatedRequestBodyController {

  public String create(@RequestBody String payload) {
    return payload;
  }
}
