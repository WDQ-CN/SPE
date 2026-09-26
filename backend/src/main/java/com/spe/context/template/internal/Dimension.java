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
}
