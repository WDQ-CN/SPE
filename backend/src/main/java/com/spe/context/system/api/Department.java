package com.spe.context.system.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * 部门 code（09 D5 / 11 §11）：大写常量，存 `sys_user.department`（VARCHAR(20)）。
 *
 * <p><b>⚠️ 为什么要带 label（2026-10-01，执行 W3.3 时发现的口径断裂）</b>：`dimension.suggested_dept`
 * 是自由文本，而 V8 的默认模板种子里填的是**中文标签**（'质量'/'交付物流'/'采购'/'服务'）；
 * `sys_user.department` 存的却是**枚举 code**。若直接拿 suggested_dept 去查用户，永远查不到人——
 * 表现是"所有评分单都未指派"，**不报错、只是静默地没人被指派**。
 *
 * <p>所以把"标签 ↔ code"的换算放在**部门 code 的权威处**（本枚举），而不是散在调用方：
 * 需要跨这两个域取值的地方一律走 {@link #resolve(String)}（code 与标签都认）。
 */
public enum Department {
  /** 质量（附录 A 的标签就是"质量"）。 */
  QUALITY("质量"),
  /** 交付物流。 */
  DELIVERY("交付物流"),
  /** 采购。 */
  PURCHASE("采购"),
  PRODUCTION("生产"),
  FINANCE("财务"),
  /** 服务。 */
  SERVICE("服务"),
  /**
   * 其他（01 §7 的权威写法是**「其他」**）。
   *
   * <p>⚠️ 曾经写成「其它」：那样 `resolve("其他")` 返回空 ⇒ 该部门维度**静默不指派**（不报错、
   * 只是没人被指派），与 04-088 同一形态（03 §34 的 P2-10）。
   */
  OTHER("其他");

  private final String label;

  Department(String label) {
    this.label = label;
  }

  /** 面向界面的中文标签（与 01 附录 A 的写法一致）。 */
  public String label() {
    return label;
  }

  /**
   * 把"部门 code 或中文标签"解析成枚举：两边都认，找不到返回空（**不猜**）。
   *
   * <p>认两种写法是刻意的：`suggested_dept` 是自由文本（历史数据里可能两种都出现过），
   * 而用户档案里一定是 code。返回空由调用方决定"置未指派 + 记日志"，而不是静默回落到某个默认部门。
   */
  public static Optional<Department> resolve(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String value = raw.trim();
    return Arrays.stream(values())
        .filter(department -> department.name().equalsIgnoreCase(value) || department.label.equals(value))
        .findFirst();
  }
}
