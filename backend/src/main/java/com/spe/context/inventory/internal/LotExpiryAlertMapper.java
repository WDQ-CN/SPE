package com.spe.context.inventory.internal;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 批次效期预警去重记录。 */
@Mapper
public interface LotExpiryAlertMapper {

  /** INSERT IGNORE：同批次同日同档只保留一条。 */
  @Insert(
      "INSERT IGNORE INTO lot_expiry_alert (lot_id, alert_date, days_before)"
          + " VALUES (#{lotId}, CURRENT_DATE, #{daysBefore})")
  int insertIfAbsent(@Param("lotId") long lotId, @Param("daysBefore") int daysBefore);
}
