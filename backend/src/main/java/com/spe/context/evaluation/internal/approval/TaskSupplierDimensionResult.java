package com.spe.context.evaluation.internal.approval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 维度结果快照（V13 / 02 §3.3 / 15 §E3）：定稿（T3）时**整批写入、之后只读**。
 *
 * <p>行数不变量：写入行数 = 该评估单的维度数，且每维 {@code score} 非空 ——
 * 不完整即断言式失败（15 §E13），不许静默产出空报告。
 * {@code dimensionName} / {@code weight} 是刻意的反范式：模板版本发布即冻结（P2 版本化保证），
 * 冗余值不会漂移，换来报告页免实时联表（05 §8.2）。
 */
@Data
@TableName("task_supplier_dimension_result")
public class TaskSupplierDimensionResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskSupplierId;

  /** 维度（模板版本内的 dimension.id）。 */
  private Long dimensionId;

  private String dimensionName;

  private BigDecimal weight;

  /** 维度分；定稿前为空（与 ScoreCalculator 的 null 口径一致：null 表示"还没算过"）。 */
  private BigDecimal score;

  private String grade;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
