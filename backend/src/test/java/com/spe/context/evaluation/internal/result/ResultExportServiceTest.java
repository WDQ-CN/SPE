package com.spe.context.evaluation.internal.result;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 导出的文本防护（M5 W5）：公式注入前缀与文件名清理。 */
class ResultExportServiceTest {

  @Test
  @DisplayName("公式注入：= + - @ TAB CR 开头前置单引号；普通文本不变；null → 空串")
  void safePrefixesFormulaTriggers() {
    assertThat(ResultExportService.safe("=1+1")).isEqualTo("'=1+1");
    assertThat(ResultExportService.safe("+SUM(A1)")).isEqualTo("'+SUM(A1)");
    assertThat(ResultExportService.safe("-2")).isEqualTo("'-2");
    assertThat(ResultExportService.safe("@cmd")).isEqualTo("'@cmd");
    assertThat(ResultExportService.safe("\tX")).isEqualTo("'\tX");
    assertThat(ResultExportService.safe("正常名称")).isEqualTo("正常名称");
    assertThat(ResultExportService.safe("a=b")).isEqualTo("a=b");
    assertThat(ResultExportService.safe(null)).isEmpty();
  }

  @Test
  @DisplayName("文件名：引号/控制字符/路径分隔符替换为下划线，中文保留")
  void stripFileNameRemovesHeaderInjection() {
    assertThat(ResultExportService.stripFileName("2026 Q2\r\nX")).isEqualTo("2026 Q2__X");
    assertThat(ResultExportService.stripFileName("a\"b\\c/d")).isEqualTo("a_b_c_d");
    assertThat(ResultExportService.stripFileName("季度评估")).isEqualTo("季度评估");
  }
}
