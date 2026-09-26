package com.spe.context.template.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 指标实体（`V7__template.sql` / 01 T3 / 02 §3）。
 *
 * <p><b>`templateVersionId` 是冗余列</b>（真正的父子关系是 {@code dimension_id}）：它存在只为让
 * 「版本内编码唯一」由唯一键 `uk_indicator_version_code` 兜底（12 §E11）——编码是 Excel 导入与开放 API
 * 定位指标的依据，靠"先查再插"在并发下会漏。代价是写入要保证两处一致，而写入路径只有两条
 * （建档、自动复制），都在同一事务内构造。
 *
 * <p>三个可空字段各有语义：{@code scoreMode} 对定性指标为空（策略由 {@code type} 决定，12 §E2）；
 * {@code targetValue} 仅 TARGET 有意义且发布时须 &gt; 0（01 §6.6）；{@code unit}/{@code standard} 见 12 §E15。
 */
@Data
@TableName("indicator")
public class Indicator {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long dimensionId;

  /** 冗余：所属模板版本，用于「版本内编码唯一」（见类注释）。 */
  private Long templateVersionId;

  private String name;

  /** 指标编码：导入与 API 的定位键；大小写按库 collation 视为冲突（12 §E11）。 */
  private String code;

  /** 权重 0~100，**维度内**合计须为 100（01 T4）。 */
  private BigDecimal weight;

  private IndicatorType type;

  /** 计分方式；定性指标为空。 */
  private ScoreMode scoreMode;

  private String unit;

  private BigDecimal targetValue;

  /** 评分标准说明：定性指标的档位文案、DIRECT 指标的人工换算表。 */
  private String standard;

  private Integer sortNo;

  /** 乐观锁版本（12 §E2/E12）。 */
  private Integer version;
}
