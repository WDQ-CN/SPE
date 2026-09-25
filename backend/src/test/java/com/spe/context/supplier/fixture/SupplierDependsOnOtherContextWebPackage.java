package com.spe.context.supplier.fixture;

import com.spe.context.system.web.SharedWebHelper;

/**
 * 故意违规的测试夹具：依赖了另一个上下文的非 api 包（system 的顶层 web 包）。
 *
 * <p>用于锁定 R-A 的<b>白名单</b>语义——05 §12 写的是「跨上下文仅允许依赖 {@code ..api..}」，
 * 只拦 {@code internal}/{@code query} 的实现捕获不到本类。见
 * {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
public class SupplierDependsOnOtherContextWebPackage {

  public String offendingDependency() {
    return new SharedWebHelper().describe();
  }
}
