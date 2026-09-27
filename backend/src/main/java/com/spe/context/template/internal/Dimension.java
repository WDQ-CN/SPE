package com.spe.context.template.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 维度实体（`V7__template.sql` / 01 T2 / 02 §3）。
 *
 * <p>权重是 {@link BigDecimal} 而不是 double：发布校验要判「**精确等于** 100.00」，浮点会让
 * 33.33+33.33+33.34 这类组合出现判不等的假失败（12 §E3）。单行范围另有 CHECK 约束兜底（V7）。
 *
 * <p>{@code isKey} 对应 `TINYINT(1)`，用 {@link Boolean} 承接。本实体只在 template 上下文内流转。
 */
@Data
@TableName("dimension")
public class Dimension {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long templateVersionId;

  private String name;

  /** 权重 0~100，**模板内**合计须为 100（01 T4）。 */
  private BigDecimal weight;

  private Integer sortNo;

  /** 建议评估部门：01 J2 要求任务发布时按它自动指派该部门下的评估人。 */
  private String suggestedDept;

  /** 是否关键维度：供预警规则②使用（01 W1）；附录 A 未标注，默认模板填 0（12 §E15）。 */
  private Boolean isKey;

  /** 乐观锁版本：维度改删的守卫（12 §E2/E12）。 */
  private Integer version;

  /**
   * **复制来源行 id**（04-081）：本行由自动复制产生时，记下它在源版本里的行 id。
   *
   * <p>为什么要它：复用同源草稿时要重建"源行 → 草稿行"的映射，而原先只能按业务键（`sort_no + name`）
   * 配对——**业务键是可编辑字段**，用户在草稿里改个名就配不上（只能报 2015），同序号同名还会配对塌陷。
   * 记下源行 id 之后，身份与业务键解耦：改名、重复都不影响定位。
   * 老数据（该列未回填）为 `NULL`，那时回退到按业务键配对。
   */
  private Long srcDimensionId;
}
