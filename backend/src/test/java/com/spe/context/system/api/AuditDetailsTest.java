package com.spe.context.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuditDetails#putIfChanged} 的口径（06 §2.8）：只记**真正变化**的字段、`null` 与空串等价、
 * 枚举按 `name()` 归一、`Map.of` 不接受 null 故一律写作空串。
 */
class AuditDetailsTest {

  @Test
  @DisplayName("值未变化则不写入（detail 里不留噪声条目）")
  void skipsUnchanged() {
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "name", "甲供应商", "甲供应商");
    AuditDetails.putIfChanged(detail, "category", null, null);

    assertThat(detail).isEmpty();
  }

  @Test
  @DisplayName("有变化时写 {before, after}")
  void recordsBeforeAfter() {
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "name", "甲供应商", "乙供应商");

    assertThat(detail).containsEntry("name", Map.of("before", "甲供应商", "after", "乙供应商"));
  }

  @Test
  @DisplayName("null 一律写作空串；null ↔ 有值视为变化")
  void normalizesNull() {
    Map<String, Object> filled = new LinkedHashMap<>();
    AuditDetails.putIfChanged(filled, "category", null, "电子");
    assertThat(filled).containsEntry("category", Map.of("before", "", "after", "电子"));

    Map<String, Object> cleared = new LinkedHashMap<>();
    AuditDetails.putIfChanged(cleared, "category", "电子", null);
    assertThat(cleared).containsEntry("category", Map.of("before", "电子", "after", ""));
  }

  @Test
  @DisplayName("枚举按 name() 归一，可直接传 Role / Department / UserStatus")
  void stringifiesEnums() {
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "role", Role.EVALUATOR, Role.MANAGER);
    AuditDetails.putIfChanged(detail, "department", Department.PURCHASE, Department.PURCHASE);

    assertThat(detail).containsOnlyKeys("role");
    assertThat(detail).containsEntry("role", Map.of("before", "EVALUATOR", "after", "MANAGER"));
  }
}
