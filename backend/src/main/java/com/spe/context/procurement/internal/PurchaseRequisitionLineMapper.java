package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 请购单行 Mapper。 */
@Mapper
public interface PurchaseRequisitionLineMapper extends BaseMapper<PurchaseRequisitionLine> {

  String COLUMNS =
      "id, requisition_id, line_no, item_id, qty, uom, estimated_price, amount";

  @Select("SELECT " + COLUMNS + " FROM purchase_requisition_line"
      + " WHERE requisition_id = #{requisitionId} ORDER BY line_no")
  List<PurchaseRequisitionLine> findByRequisitionId(@Param("requisitionId") long requisitionId);

  @Delete("DELETE FROM purchase_requisition_line WHERE requisition_id = #{requisitionId}")
  int deleteByRequisitionId(@Param("requisitionId") long requisitionId);
}
