package com.spe.context.evaluation.fixture;

import org.apache.ibatis.annotations.Update;

/**
 * 故意违规的测试夹具，一次踩两条 R-B：以 {@code QueryMapper} 命名却位于只读上下文之外（evaluation），
 * 且带写注解。见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
public interface LeakyQueryMapper {

  @Update("update supplier set status = 'DISABLED'")
  int disableAll();
}
