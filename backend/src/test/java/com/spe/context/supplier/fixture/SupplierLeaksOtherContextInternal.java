package com.spe.context.supplier.fixture;

import com.spe.context.system.internal.security.JwtService;

/**
 * 故意违规的测试夹具：一个上下文直接依赖另一个上下文的 internal 包（违反 ArchUnit R-A）。
 * 只用于验证规则不是空转，见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
public class SupplierLeaksOtherContextInternal {

  public Class<?> offendingDependency() {
    return JwtService.class;
  }
}
