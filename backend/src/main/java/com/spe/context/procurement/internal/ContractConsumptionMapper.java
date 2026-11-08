package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 合同消耗 Mapper。 */
@Mapper
public interface ContractConsumptionMapper extends BaseMapper<ContractConsumption> {

  String COLUMNS = "id, contract_line_id, po_id, po_line_id, qty, amount, consumed_at";

  @Select(
      "SELECT " + COLUMNS + " FROM contract_consumption WHERE contract_line_id = #{lineId}")
  List<ContractConsumption> findByLineId(@Param("lineId") long lineId);

  @Select(
      "SELECT c.id, c.contract_line_id, c.po_id, c.po_line_id, c.qty, c.amount, c.consumed_at"
          + " FROM contract_consumption c"
          + " JOIN purchase_contract_line l ON l.id = c.contract_line_id"
          + " WHERE l.contract_id = #{contractId} ORDER BY c.id DESC")
  List<ContractConsumption> findByContractId(@Param("contractId") long contractId);

  @Delete("DELETE FROM contract_consumption WHERE po_id = #{poId}")
  int deleteByPo(@Param("poId") long poId);

  @Select(
      "SELECT COALESCE(SUM(qty), 0) FROM contract_consumption WHERE contract_line_id = #{lineId}")
  BigDecimal sumQtyByLine(@Param("lineId") long lineId);

  @Select(
      """
      SELECT COALESCE(SUM(c.qty), 0) FROM contract_consumption c
      JOIN purchase_contract_line l ON l.id = c.contract_line_id
      WHERE l.contract_id = #{contractId}
      """)
  BigDecimal sumQtyByContract(@Param("contractId") long contractId);

  @Select(
      """
      SELECT COALESCE(SUM(c.amount), 0) FROM contract_consumption c
      JOIN purchase_contract_line l ON l.id = c.contract_line_id
      WHERE l.contract_id = #{contractId}
      """)
  BigDecimal sumAmountByContract(@Param("contractId") long contractId);
}
