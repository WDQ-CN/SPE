package com.spe.context.alert.fixture;

import com.spe.context.analytics.fixture.CyclePartner;

/**
 * 故意违规的测试夹具：与 analytics 的夹具互相依赖，构成上下文之间的循环（违反 ArchUnit R-G）。
 * 只用于验证规则不是空转，见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
public class CycleStarter {

  public Class<?> offendingDependency() {
    return CyclePartner.class;
  }
}
