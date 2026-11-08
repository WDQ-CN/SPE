package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 库位容量（V1016；item_id=0 表示库位总件数上限）。 */
@Data
@TableName("location_capacity")
public class LocationCapacity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long locationId;
  private Long itemId;
  private BigDecimal maxQty;
  private String uom;
  private MasterDataStatus status;
  private Integer version;
  private LocalDateTime createdAt;
}
