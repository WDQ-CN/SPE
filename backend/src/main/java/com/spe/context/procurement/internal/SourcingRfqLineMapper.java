package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** RFQ 行 Mapper。 */
@Mapper
public interface SourcingRfqLineMapper extends BaseMapper<SourcingRfqLine> {

  String COLUMNS =
      "id, rfq_id, line_no, item_id, qty, uom, need_date, target_price, spec";

  @Select("SELECT " + COLUMNS + " FROM sourcing_rfq_line WHERE rfq_id = #{rfqId} ORDER BY line_no")
  List<SourcingRfqLine> findByRfqId(@Param("rfqId") long rfqId);

  @Select("SELECT " + COLUMNS + " FROM sourcing_rfq_line WHERE id = #{id}")
  java.util.Optional<SourcingRfqLine> findById(@Param("id") long id);
}
