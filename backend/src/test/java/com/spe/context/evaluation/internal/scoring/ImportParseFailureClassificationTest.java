package com.spe.context.evaluation.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.excel.exception.ExcelAnalysisException;
import com.alibaba.excel.exception.ExcelCommonException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-6（03 §38）的判据单测：哪些异常才配得上"文件无法解析"这句话。
 *
 * <p>为什么单测而不是集成测：要证的是一条**分类规则**，而"让 Collector 在生产代码里恰好崩一次 NPE"
 * 那种夹具既造不真也留不长。集成侧另有真坏 xlsx ⇒ 2026 的用例（{@code ScoreImportServiceIntegrationTest}）。
 */
class ImportParseFailureClassificationTest {

  @Test
  @DisplayName("文件级失败的正例：EasyExcel 的解析异常（含 IO 根因）认作 2026")
  void wrapsIoFailureIsFileLevel() {
    assertThat(ScoreImportService.isFileLevelParseFailure(new ExcelAnalysisException("坏 zip", new IOException("截断"))))
        .isTrue();
    assertThat(ScoreImportService.isFileLevelParseFailure(new ExcelCommonException("没有 sheet")))
        .isTrue();
  }

  /**
   * 这条就是 P2-6 的全部意义：EasyExcel 会把 listener 抛出的异常**也**包进 ExcelAnalysisException，
   * 所以"只把 catch 的类型换窄"判不出这一层——必须看根因。
   */
  @Test
  @DisplayName("被包装的代码缺陷不算文件问题：NPE / 越界 / 非法状态 / 类型转换 ⇒ 原样上浮")
  void codeDefectsInsideWrapperAreNotFileLevel() {
    assertThat(
            ScoreImportService.isFileLevelParseFailure(
                new ExcelAnalysisException("listener 崩了", new NullPointerException())))
        .as("NPE 被洗成「文件无法解析」就是 P2-6 描述的那个病")
        .isFalse();
    assertThat(
            ScoreImportService.isFileLevelParseFailure(
                new ExcelAnalysisException("listener 崩了", new IndexOutOfBoundsException())))
        .isFalse();
    assertThat(ScoreImportService.isFileLevelParseFailure(new IllegalStateException("状态机不该走到这里")))
        .isFalse();
    assertThat(ScoreImportService.isFileLevelParseFailure(new ClassCastException("不是本行的类型")))
        .isFalse();
  }

  @Test
  @DisplayName("第三条路：既不是解析异常也不是代码缺陷 ⇒ 不认作文件级（交给 5001，别猜）")
  void unknownRuntimeExceptionIsNotClaimedAsFileLevel() {
    assertThat(ScoreImportService.isFileLevelParseFailure(new RuntimeException("未知")))
        .as("认不出来就原样上浮：宁多一个 5001，不可把缺陷洗成用户错误")
        .isFalse();
  }

  @Test
  @DisplayName("自引用的 cause 链不会让判定死循环")
  void selfReferencingCauseTerminates() {
    // `initCause(this)` 会被 JDK 直接拒绝（"Self-causation not permitted"），所以用覆写造一个自引用链
    Throwable loop =
        new RuntimeException("自引用") {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };

    assertThat(ScoreImportService.isFileLevelParseFailure(loop)).isFalse();
  }
}
