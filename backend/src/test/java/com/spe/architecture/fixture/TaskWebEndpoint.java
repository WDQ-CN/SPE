package com.spe.architecture.fixture;

import org.springframework.web.bind.annotation.RestController;

/**
 * **类名不以 `Controller` 结尾**、但确实是 `@RestController` 且漏写类级 `@PreAuthorize` 的夹具。
 *
 * <p>它存在的唯一理由（03 §38 的 P2-12）：R-H 原先按**类名后缀**选取，于是"换个类名"（`XxxWeb`、
 * `XxxEndpoint`）就能绕过「端点默认拒绝」——而这条规则要防的恰恰是"漏写注解静默越权"。
 * 改成按注解选取后，这个夹具必须被抓住；用旧的选取器它**永远不会被抓住**，等效性用例也照样绿
 * （那正是假守卫）。
 *
 * <p>它同时违反 R-E（Controller 归属 `..web..`）——不影响，`assertCatches` 只要求失败报告点名本夹具。
 */
@RestController
public class TaskWebEndpoint {

  public String list() {
    return "annotated as a controller, named otherwise, no @PreAuthorize";
  }
}
