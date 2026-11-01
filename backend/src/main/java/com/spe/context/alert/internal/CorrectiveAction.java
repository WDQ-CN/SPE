package com.spe.context.alert.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 整改任务（V1004；29 §3.1）。
 *
 * <p>一条预警最多一条整改（`uk_action_alert`）；基线周期/分数在下单时冻结，后续模板或评分变化不改写它。
 * 状态流转由 {@link CorrectiveActionStateMachine} 统一判定，Service 再用带前置状态的 UPDATE 落库。
 */
@Data
@TableName("corrective_action")
public class CorrectiveAction {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long alertId;

  private Long taskSupplierId;

  private Long supplierId;

  /** 关键维度整改的维度 id；总分/趋势为空。 */
  private Long dimensionId;

  private Integer baselineYear;

  private Integer baselineQuarter;

  private BigDecimal baselineScore;

  private BigDecimal targetScore;

  private Long ownerId;

  private LocalDate dueDate;

  private String measure;

  private String expectedResult;

  /** {@link CorrectiveActionStatus} 的字符串值，与 V1004 的列注释逐字一致。 */
  private String status;

  private String submitComment;

  private LocalDateTime submittedAt;

  private Integer verifyYear;

  private Integer verifyQuarter;

  private Long verifyTaskSupplierId;

  private BigDecimal verifyScore;

  /** IMPROVED / NOT_IMPROVED / PENDING。 */
  private String verifyOutcome;

  private Long verifiedBy;

  private String verifyComment;

  private LocalDateTime verifiedAt;

  private String canceledReason;

  private Long createdBy;

  private Integer version;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
