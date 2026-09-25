package com.spe.context.analytics.fixture;

import com.spe.context.alert.fixture.CycleStarter;

/**
 * 故意违规的测试夹具：与 alert 的夹具互相依赖，构成上下文之间的循环（违反 ArchUnit R-G）。
 * 只用于验证规则不是空转，见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
public class CyclePartner {

  public Class<?> offendingDependency() {
    return CycleStarter.class;
  }
}
