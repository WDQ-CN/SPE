package com.spe.architecture.fixture;

/**
 * 故意**漏写类级 `@PreAuthorize`** 的 Controller 夹具：用于证明「端点默认拒绝」那条规则真的会失败
 * （见 {@code ArchUnitRulesAreEffectiveTest}）。
 *
 * <p>只违反这一条：不加 `@RestController` 就不会被 R-E（Controller 归属）命中，也不含
 * `@RequestBody` 参数，所以不会牵连入参校验那条规则。
 */
public class AuthorizationMissingController {

  public String list() {
    return "no authorization declared";
  }
}
