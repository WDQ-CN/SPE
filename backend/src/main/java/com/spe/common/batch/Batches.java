package com.spe.common.batch;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量写入的**分片单点**（03 §38 的 P1-9；CLAUDE 默认约定第 3 条"一个数只能有一处定义"）。
 *
 * <p>它管两件事，而且**是同一个数**：
 * <ol>
 *   <li>一次批量入参的业务上限——"一个任务最多勾选多少家供应商"（01 §2 的规模口径就是 500）；
 *   <li>一条多值 {@code INSERT ... VALUES (…),(…)…} 最多带多少行——超过它就切分。
 * </ol>
 * 两者刻意同源：撞 {@code max_allowed_packet} 的那条超长 INSERT，正是"一次塞进 500 家供应商 ×
 * 4 个维度 = 2000 行"造出来的；把上限与分片阈值写成两个数，就会有第三个人在中间挑一个"经验值"。
 * 这个同源关系由 {@code TaskBatchSplitTest} 里那条**反射读 {@code @Size.max} 与 {@link #MAX_ROWS} 相等**
 * 的用例钉住——它红了就是有人在两处各写了一遍数字。
 *
 * <p>⚠️ 分片**不改变事务边界**：调用方必须在同一个 {@code @Transactional} 里循环提交各批，
 * 否则会出现"任务已发布、部分评分单缺失"的半成品（这条由"第 2 批抛错 ⇒ 全部回滚"的用例钉住）。
 *
 * <p>不引 {@code ExecutorType.BATCH}：它会让 {@code @Options(useGeneratedKeys)} 的回填语义变得不确定，
 * 而发布路径紧接着要用 {@code TaskSupplier.getId()} 去建评分单。
 */
public final class Batches {

  /** 单条批量 INSERT 的最大行数，同时也是集合入参的业务上限。两处必须引用这一个常量。 */
  public static final int MAX_ROWS = 500;

  private Batches() {
    // 工具类：只暴露静态方法
  }

  /**
   * 按 {@code maxSize} 切分，**保持原有顺序**；返回的是原列表的视图片段，所以
   * {@code useGeneratedKeys} 回填的 id 会落到调用方手里的那批对象上。
   *
   * @param rows 待切分的行（{@code null} 或空列表 ⇒ 返回空列表，即"一批都不发"）
   * @param maxSize 每批最多几行，必须为正数（一般就传 {@link #MAX_ROWS}）
   */
  public static <T> List<List<T>> split(List<T> rows, int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("分片大小必须是正数，实际=" + maxSize);
    }
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<List<T>> batches = new ArrayList<>((rows.size() + maxSize - 1) / maxSize);
    for (int from = 0; from < rows.size(); from += maxSize) {
      batches.add(rows.subList(from, Math.min(from + maxSize, rows.size())));
    }
    return batches;
  }
}
