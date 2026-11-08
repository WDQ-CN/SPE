package com.spe.context.finance.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 会计分录行（V1010；借贷二选一）。 */
@Data
@TableName("finance_journal_line")
public class JournalLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long entryId;
  private Integer lineNo;
  private Long accountId;
  private BigDecimal debit;
  private BigDecimal credit;
  private String description;
}
