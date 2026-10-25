package com.spe.common.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** LIKE 转义单点（03 §41.3 的 P2-37）。 */
class SqlKeywordsTest {

  @Test
  @DisplayName("转义 LIKE 通配符：% 与 _ 变字面；反斜杠必须先被双写")
  void escapesWildcardsAndBackslash() {
    assertThat(SqlKeywords.escapeLike("100%")).isEqualTo("100\\%");
    assertThat(SqlKeywords.escapeLike("A_B")).isEqualTo("A\\_B");
    assertThat(SqlKeywords.escapeLike("a\\b")).isEqualTo("a\\\\b");
    assertThat(SqlKeywords.escapeLike("%_\\")).isEqualTo("\\%\\_\\\\");
    assertThat(SqlKeywords.escapeLike("普通关键字")).isEqualTo("普通关键字");
    assertThat(SqlKeywords.escapeLike("")).isEmpty();
    assertThat(SqlKeywords.escapeLike(null)).isNull();
  }
}
