package com.spe.context.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阈值约束的边界锁定（01 §6.3 / 02 §7）：纯值对象，不启 Spring 上下文（09 D7）。
 *
 * <p>重点是「允许相等 = 停用该档」这一条——它最容易被实现成严格递增（10 §6 风险行）。
 */
class GradeThresholdsTest {

  private static GradeThresholds of(String a, String b, String c, String d) {
    return new GradeThresholds(
        new BigDecimal(a), new BigDecimal(b), new BigDecimal(c), new BigDecimal(d));
  }

  @Test
  @DisplayName("出厂默认 90/80/70/0 满足约束（01 §6.3）")
  void defaultsSatisfyOrdering() {
    assertThat(GradeThresholds.defaults().satisfiesOrdering()).isTrue();
  }

  @Test
  @DisplayName("允许相等：任两档相等即停用该档，仍算合法（01 §6.3）")
  void equalThresholdsDisableTheGradeButStayValid() {
    assertThat(of("90", "90", "70", "0").satisfiesOrdering()).isTrue();
    assertThat(of("90", "80", "80", "0").satisfiesOrdering()).isTrue();
    assertThat(of("100", "100", "100", "100").satisfiesOrdering()).isTrue();
    assertThat(of("0", "0", "0", "0").satisfiesOrdering()).isTrue();
  }

  @Test
  @DisplayName("边界取值 0 与 100 合法，越界非法")
  void boundsAreInclusive() {
    assertThat(of("100", "80", "70", "0").satisfiesOrdering()).isTrue();
    assertThat(of("101", "80", "70", "0").satisfiesOrdering()).isFalse();
    assertThat(of("90", "80", "70", "-1").satisfiesOrdering()).isFalse();
  }

  @Test
  @DisplayName("顺序颠倒非法：D ≤ C ≤ B ≤ A")
  void orderingViolationsAreRejected() {
    assertThat(of("80", "90", "70", "0").satisfiesOrdering()).isFalse();
    assertThat(of("90", "70", "80", "0").satisfiesOrdering()).isFalse();
    assertThat(of("90", "80", "60", "70").satisfiesOrdering()).isFalse();
  }

  @Test
  @DisplayName("缺失档位直接非法（不入库半截阈值）")
  void nullFieldIsInvalid() {
    assertThat(new GradeThresholds(null, new BigDecimal("80"), new BigDecimal("70"), BigDecimal.ZERO)
            .satisfiesOrdering())
        .isFalse();
  }
}
