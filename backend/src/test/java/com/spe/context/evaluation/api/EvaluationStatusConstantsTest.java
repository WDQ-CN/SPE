package com.spe.context.evaluation.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 状态常量的机械化对齐（13 §E3 的配套保证；06 §2.1 是状态常量的权威）。
 *
 * <p>三个枚举的常量集合必须与 02 §3.1 的状态清单<b>逐值相等</b>——"文档改了枚举没改"或
 * "枚举悄悄多/少一个值"都会在这里变红。
 *
 * <p>为什么用<b>显式清单</b>而不是"解析 02 §3.1 的 Markdown 再比对"：规格是散文（表格排版随时会变），
 * 解析它会把测试绑在排版上、并制造"我解析对了"的假信心；写成清单则是**一次人工同步 + 永久机械守卫**，
 * 两侧任一改动都必须同时改这里，而这里改错会在评审时被另一侧看见。
 */
class EvaluationStatusConstantsTest {

  @Test
  @DisplayName("任务状态：常量与 02 §3.1 / 06 §2.1 的清单逐值一致（COMPLETED 与 CANCELED 仅预留）")
  void evalTaskStatusHasExactlyTheSpecifiedConstants() {
    assertThat(names(EvalTaskStatus.values()))
        .as("02 §3.1 的 eval_task.status 清单")
        .containsExactlyInAnyOrder("DRAFT", "IN_PROGRESS", "COMPLETED", "CANCELED");
  }

  @Test
  @DisplayName("评估单状态：常量与 02 §3.1 / 06 §2.1 的清单逐值一致（APPROVING 与 EFFECTIVE 仅预留）")
  void taskSupplierStatusHasExactlyTheSpecifiedConstants() {
    assertThat(names(TaskSupplierStatus.values()))
        .as("02 §3.1 的 task_supplier.status 清单")
        .containsExactlyInAnyOrder("SCORING", "PENDING_APPROVAL", "APPROVING", "EFFECTIVE");
  }

  @Test
  @DisplayName("评分单状态：常量与 02 §3.1 / 06 §2.1 的清单逐值一致（三态在 P3 全部可达）")
  void sheetStatusHasExactlyTheSpecifiedConstants() {
    assertThat(names(SheetStatus.values()))
        .as("02 §3.1 的 sheet.status 清单")
        .containsExactlyInAnyOrder("UNASSIGNED", "SCORING", "SUBMITTED");
  }

  @Test
  @DisplayName("三个枚举的常量名一律是 06 §2.1 要求的大写下划线形式（落库字符串即枚举名）")
  void allConstantNamesFollowTheNamingRule() {
    List<String> all =
        Arrays.asList(
            names(EvalTaskStatus.values()),
            names(TaskSupplierStatus.values()),
            names(SheetStatus.values()))
            .stream()
            .flatMap(List::stream)
            .toList();
    assertThat(all).as("至少要扫到 11 个常量，否则说明用例本身没读到枚举").hasSize(11);
    assertThat(all).allSatisfy(name -> assertThat(name).matches("[A-Z][A-Z_]*"));
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }
}
