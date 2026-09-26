package com.spe.context.supplier.web.fixture;

import com.spe.context.template.api.TemplateSnapshotFacade;
import org.springframework.web.bind.annotation.RestController;

/**
 * R-E（续）`controllersStayInOwnContext` 的违规夹具：Controller 故意依赖**其他上下文**。
 *
 * <p>为什么依赖对方的 **api** 包而不是 internal：这条规则用的是 `dependOnOtherContextNonApi(true)`
 * ——`wholeContext=true` 那一分支，**连对方 api 都不许依赖**，而普通类（R-A）只禁非 api。所以
 * 「只依赖对方 api」恰好只违反这一条规则，`wholeContext` 分支写反（true→false）或匹配写空时，
 * 本夹具对应的用例就会变红。若改成依赖对方 internal，则 true/false 两种实现**都会**命中它，
 * 这个分支的区别就永远测不出来（03 §26 的 P1-3）。
 *
 * <p>为什么放在 `supplier.web.fixture` 而不是 `supplier.fixture`：ArchUnit 的夹具集是「一次性全量导入、
 * 每条规则各报各的」，夹具要尽量**只违反它负责的那一条**；放 `supplier.fixture` 会同时触发 R-E 的
 * 「Controller 不在 web 子包」，两个用例的失败报告就不干净了。
 */
@RestController
public class SupplierWebControllerWithForeignContextDependency {

  /** 仅为制造一条指向其他上下文 api 的编译期依赖，无任何运行时用途。 */
  @SuppressWarnings("unused")
  private final TemplateSnapshotFacade foreignContextDependency = null;
}
