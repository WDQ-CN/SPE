package com.spe.common.page;

/**
 * 分页入参的规范化（09 D8 / 06 §4）：`page` 下界为 1、`size` 收敛到 `[1, MAX_SIZE]`，并给出 SQL 需要的
 * `OFFSET`。
 *
 * <p>把「收敛规则」收在一处：原先用户与供应商两个 Service 各写一遍同样的三行，规则一旦要改
 * （例如调上限）就会漏改一处（03 §15 的 R10-3）。本类只做规范化与换算，不依赖 Spring 与 Mapper。
 *
 * <p>与 {@link PageResult} 配对：`PageQuery` 管入参、`PageResult` 管出参。
 */
public record PageQuery(int page, int size) {

  /** 单页上限：防止 `size` 被用作全表拉取。P3 若需一次取更大批量，改这里并同步评估保护。 */
  public static final int MAX_SIZE = 100;

  /** 默认单页大小，与前端分页组件的默认值一致。 */
  public static final int DEFAULT_SIZE = 10;

  /** 在紧凑构造器里收敛，保证「拿到 PageQuery 就一定是合法分页」。 */
  public PageQuery {
    page = Math.max(page, 1);
    size = Math.min(Math.max(size, 1), MAX_SIZE);
  }

  public static PageQuery of(int page, int size) {
    return new PageQuery(page, size);
  }

  /** SQL `OFFSET`：`page` 从 **1** 开始（09 D8），故偏移是 `(page - 1) * size`。 */
  public long offset() {
    return (long) (page - 1) * size;
  }
}
