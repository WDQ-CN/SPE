package com.spe.common.fixture;

import com.spe.context.system.internal.security.JwtService;

/**
 * 故意违规的测试夹具：common 依赖业务上下文（违反 ArchUnit R-F）。
 * 只用于验证规则不是空转（见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}），
 * 正式规则通过 DoNotIncludeTests 排除测试类，因此本包不影响真实校验。正式代码不要模仿。
 */
public class CommonDependingOnContext {

  public Class<?> offendingDependency() {
    return JwtService.class;
  }
}
