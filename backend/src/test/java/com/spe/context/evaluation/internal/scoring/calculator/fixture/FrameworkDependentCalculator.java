package com.spe.context.evaluation.internal.scoring.calculator.fixture;

import org.springframework.stereotype.Component;

/**
 * 故意违规的测试夹具：计分器所在包依赖了 Spring（违反 R-C 的纯函数要求）。
 * 见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
@Component
public class FrameworkDependentCalculator {
}
