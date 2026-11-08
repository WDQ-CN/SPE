package com.spe.context.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CSV 导出转义：引号/逗号/换行之外，公式前缀必须去毒。 */
class ReportServiceTest {

  @Test
  @DisplayName("公式前缀加单引号，普通值与数字保持原样")
  void neutralizesFormulaPrefixes() {
    assertThat(ReportService.escape("=1+1")).isEqualTo("'=1+1");
    assertThat(ReportService.escape("+SUM(A1)")).isEqualTo("'+SUM(A1)");
    assertThat(ReportService.escape("-5")).isEqualTo("'-5");
    assertThat(ReportService.escape("@cmd")).isEqualTo("'@cmd");
    assertThat(ReportService.escape("正常值")).isEqualTo("正常值");
    assertThat(ReportService.escape(new BigDecimal("10.50"))).isEqualTo("10.50");
  }

  @Test
  @DisplayName("逗号、引号、换行按 CSV 规则加引号")
  void quotesSpecialCharacters() {
    assertThat(ReportService.escape("a,b")).isEqualTo("\"a,b\"");
    assertThat(ReportService.escape("a\"b")).isEqualTo("\"a\"\"b\"");
    assertThat(ReportService.escape("a\nb")).isEqualTo("\"a\nb\"");
  }
}
