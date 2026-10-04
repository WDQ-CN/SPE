package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.evaluation.api.EvalTaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估任务实体（V11__evaluation.sql / 02 §3.1 / 01 J1~J4）。
 *
 * <p>状态机只有一条 P3 可达的迁移：{@code DRAFT → IN_PROGRESS}（发布，05 §5.5 T1）。{@code COMPLETED} 与
 * {@code CANCELED} 按 13 §E3「枚举预留、入口后置」留着——**P3 不写任何进入它们的代码**。
 *
 * <p>{@code version} 是乐观锁：草稿编辑与发布都用 `status + version` 双重守卫（06 §2.4），
 * 0 行统一翻成 3001（并发下别人先改了）或 2020（状态本来就不允许，13 §E20 的口径）。
 */
@Data
@TableName("eval_task")
public class EvalTask {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;

  /** 周期-年（与 quarter 一起构成周期；J4 的唯一性按它判定）。 */
  private Integer year;

  private Integer quarter;

  /** 绑定的**已发布**模板版本（01 J1）。 */
  private Long templateVersionId;

  private LocalDate deadline;

  private EvalTaskStatus status;

  private Integer version;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
