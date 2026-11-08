package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** BOM 行（单位用量）。 */
@Data
@TableName("bom_line")
public class BomLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long bomId;
  private Integer lineNo;
  private Long componentItemId;
  private BigDecimal qty;
  private String uom;
}
