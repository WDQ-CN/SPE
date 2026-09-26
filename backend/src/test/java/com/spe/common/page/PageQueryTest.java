package com.spe.common.page;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PageQuery} 的入参规范化与偏移换算（09 D8）：两个 Service 的分页边界从此只有一处定义。 */
class PageQueryTest {

  @Test
  @DisplayName("page 下界收敛为 1；size 收敛到 [1, MAX_SIZE]")
  void clampsBounds() {
    assertThat(PageQuery.of(0, 0).page()).isEqualTo(1);
    assertThat(PageQuery.of(0, 0).size()).isEqualTo(1);
    assertThat(PageQuery.of(-5, -1).page()).isEqualTo(1);
    assertThat(PageQuery.of(-5, -1).size()).isEqualTo(1);
    assertThat(PageQuery.of(2, 9999).size()).isEqualTo(PageQuery.MAX_SIZE);
    assertThat(PageQuery.of(2, PageQuery.MAX_SIZE + 1).size()).isEqualTo(PageQuery.MAX_SIZE);
  }

  @Test
  @DisplayName("offset 按「page 从 1 开始」换算；大页码不溢出 int")
  void computesOffset() {
    assertThat(PageQuery.of(1, 10).offset()).isZero();
    assertThat(PageQuery.of(3, 10).offset()).isEqualTo(20L);
    // (page-1) * size 用 long 算：int 乘法在这里会溢出
    assertThat(PageQuery.of(Integer.MAX_VALUE, PageQuery.MAX_SIZE).offset())
        .isEqualTo((long) (Integer.MAX_VALUE - 1) * PageQuery.MAX_SIZE);
  }

  @Test
  @DisplayName("合法入参原样保留")
  void keepsValidValues() {
    PageQuery query = PageQuery.of(4, 25);
    assertThat(query.page()).isEqualTo(4);
    assertThat(query.size()).isEqualTo(25);
    assertThat(query.offset()).isEqualTo(75L);
  }
}
