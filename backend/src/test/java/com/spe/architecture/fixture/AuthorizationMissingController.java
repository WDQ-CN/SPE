package com.spe.architecture.fixture;

import org.springframework.web.bind.annotation.RestController;

/**
 * 故意**漏写类级 `@PreAuthorize`** 的 Controller 夹具：用于证明「端点默认拒绝」那条规则真的会失败
 * （见 {@code ArchUnitRulesAreEffectiveTest}）。
 *
 * <p>⚠️ 2026-10-17（03 §38 的 P2-12）：规则从"按类名后缀选取"改成"按 `@RestController` 注解选取"，
 * 所以夹具必须**真的带上**那个注解，否则它不再被选取、等效性用例会因为"什么都没抓到"而假绿。
 * 它不含 `@RequestBody` 参数，因此仍只违反这一条。
 */
@RestController
public class AuthorizationMissingController {

  public String list() {
    return "no authorization declared";
  }
}
