package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 不合格品报告（NCR）。 */
@Data
@TableName("ncr")
public class Ncr {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String ncrNo;
  private Long companyId;
  private String sourceType;
  private Long sourceId;
  private Long inspectionId;
  private Long itemId;
  private Long supplierId;
  private String lotNo;
  private BigDecimal qty;
  private String defect;
  private NcrStatus status;
  private NcrDisposition disposition;
  private String reason;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime dispositionedAt;
  private LocalDateTime closedAt;
}
