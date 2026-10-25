package com.spe.common.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.context.evaluation.internal.task.ApprovalNode;
import com.spe.context.evaluation.internal.web.dto.CreateTaskRequest;
import com.spe.context.evaluation.internal.web.dto.UpdateTaskRequest;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** `Batches` 的分片语义，与"500 只有一个定义"的那条漂移守卫（CLAUDE 默认约定第 3 条）。 */
class BatchesTest {

  @Test
  @DisplayName("split：按 maxSize 切、保持顺序、批数可数")
  void splitKeepsOrderAndSize() {
    List<Integer> rows = new ArrayList<>();
    for (int i = 1; i <= 11; i++) {
      rows.add(i);
    }

    List<List<Integer>> batches = Batches.split(rows, 5);

    assertThat(batches).hasSize(3);
    assertThat(batches.get(0)).containsExactly(1, 2, 3, 4, 5);
    assertThat(batches.get(1)).containsExactly(6, 7, 8, 9, 10);
    assertThat(batches.get(2)).containsExactly(11);
    assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(5));
  }

  @Test
  @DisplayName("split：空输入 ⇒ 一批都不发（不能返回含空批的列表，否则会拼出 `VALUES` 后无内容的 SQL）")
  void splitOfEmptyInputYieldsNoBatches() {
    assertThat(Batches.split(List.of(), Batches.MAX_ROWS)).isEmpty();
    assertThat(Batches.split(null, Batches.MAX_ROWS)).isEmpty();
  }

  @Test
  @DisplayName("split：返回的是原列表的视图 ⇒ 回填到元素上的 id 调用方看得见")
  void splitReturnsViewsOfTheSourceList() {
    List<StringBuilder> rows = List.of(new StringBuilder("a"), new StringBuilder("b"));

    List<List<StringBuilder>> batches = Batches.split(rows, 1);

    assertThat(batches).hasSize(2);
    batches.get(0).get(0).append("- touched");
    assertThat(rows.get(0)).hasToString("a- touched");
  }

  @Test
  @DisplayName("split：非正数的批大小直接拒绝（不静默退化成一批装完）")
  void splitRejectsNonPositiveSize() {
    assertThatThrownBy(() -> Batches.split(List.of("x"), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("正数");
    assertThatThrownBy(() -> Batches.split(List.of("x"), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 漂移守卫：`@Size(max = …)` 与分片阈值必须是**同一个数**。这条用例就是那个"看住了"的证据——
   * 谁把 500 抄成第二个字面量（或把注解改成别的数），这里立刻红。
   */
  @Test
  @DisplayName("500 与 10 都只有一个定义：DTO 上的 @Size.max 必须等于常量")
  void sizeAnnotationsReferenceTheSameConstants() throws Exception {
    assertThat(sizeMax(CreateTaskRequest.class, "supplierIds"))
        .as("创建任务的供应商上限必须就是分片阈值")
        .isEqualTo(Batches.MAX_ROWS);
    assertThat(sizeMax(UpdateTaskRequest.class, "supplierIds"))
        .as("编辑草稿与创建必须是同一个上限")
        .isEqualTo(Batches.MAX_ROWS);
    assertThat(sizeMax(CreateTaskRequest.class, "approverIds"))
        .as("审批链上限必须就是 ApprovalNode.MAX_LEVELS")
        .isEqualTo(ApprovalNode.MAX_LEVELS);
    assertThat(sizeMax(UpdateTaskRequest.class, "approverIds")).isEqualTo(ApprovalNode.MAX_LEVELS);
    assertThat(ApprovalNode.MAX_LEVELS).as("01 没给审批链上限，这里是防御性的 10（18 §6 裁决点 5）").isEqualTo(10);
  }

  /** 读 record 组件上的 `@Size`（组件注解会传播到同名字段上）。 */
  private static int sizeMax(Class<?> type, String component) throws Exception {
    Field field = type.getDeclaredField(component);
    Size size = field.getAnnotation(Size.class);
    assertThat(size).as("%s.%s 必须带 @Size 上限", type.getSimpleName(), component).isNotNull();
    return size.max();
  }
}
