package com.spe.context.supplier.fixture;

import org.springframework.web.bind.annotation.RestController;

/**
 * 故意违规的测试夹具：Controller 未落在所在上下文的 web 子包（违反 ArchUnit R-E）。
 * 只用于验证规则不是空转，见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
@RestController
public class ControllerOutsideWebPackage {
}
