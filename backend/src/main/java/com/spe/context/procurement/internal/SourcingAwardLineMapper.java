package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 定标行 Mapper。 */
@Mapper
public interface SourcingAwardLineMapper extends BaseMapper<SourcingAwardLine> {

  String COLUMNS =
      "id, award_id, rfq_line_id, quote_line_id, awarded_qty, unit_price, amount";

  @Select("SELECT " + COLUMNS + " FROM sourcing_award_line WHERE award_id = #{awardId} ORDER BY id")
  List<SourcingAwardLine> findByAwardId(@Param("awardId") long awardId);

  @Select(
      """
      SELECT COALESCE(SUM(l.awarded_qty), 0)
      FROM sourcing_award_line l
      JOIN sourcing_award a ON a.id = l.award_id
      WHERE l.rfq_line_id = #{rfqLineId} AND a.status <> 'CANCELED'
      """)
  BigDecimal sumAwardedByRfqLine(@Param("rfqLineId") long rfqLineId);
}
