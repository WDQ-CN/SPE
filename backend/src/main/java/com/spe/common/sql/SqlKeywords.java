package com.spe.common.sql;

/**
 * LIKE 关键字的**转义单点**（03 §41.3 的 P2-37）：`%` 与 `_` 是通配符，用户输入必须按字面匹配。
 *
 * <p>为什么要有它：三处 keyword 查询（结果入口 `ResultTaskMapper`、任务列表 `EvalTaskMapper`、
 * 供应商列表 `SupplierMapper`）原先都把用户输入直接拼进 {@code CONCAT('%', ?, '%')}——搜「100%」
 * 会退化成全匹配，搜「_」甚至会命中一切。三处是同一根因，按 `21 §3.W1-5` 一并收口到这一个函数。
 *
 * <p>MySQL 的 LIKE 默认以反斜杠为转义符（`NO_BACKSLASH_ESCAPES` 未开启），故把 `\` 自身也先转义，
 * 顺序必须是**先反斜杠后通配符**，否则 `\%` 会被二次转义成 `\\%`（即"反斜杠 + 通配符"）。
 */
public final class SqlKeywords {

  private SqlKeywords() {
    // 工具类：只暴露静态方法
  }

  /**
   * 把用户输入转义成 LIKE 的字面量片段（不含两侧的 `%`，由 SQL 的 {@code CONCAT} 负责）。
   *
   * @param keyword 原始输入；{@code null} 原样返回（SQL 侧按"无关键字"分支跳过）
   */
  public static String escapeLike(String keyword) {
    if (keyword == null) {
      return null;
    }
    return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
