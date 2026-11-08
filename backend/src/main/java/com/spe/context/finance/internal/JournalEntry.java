package com.spe.context.finance.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 会计凭证（V1010；docs/erp/34、48）。 */
@Data
@TableName("finance_journal_entry")
public class JournalEntry {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private Long companyId;
  private LocalDate entryDate;
  private String sourceType;
  private Long sourceId;
  private String currency;
  private String description;
  private BigDecimal totalAmount;
  private String status;
  private Long createdBy;
  private LocalDateTime postedAt;
  private Integer version;
  private LocalDateTime createdAt;
}
