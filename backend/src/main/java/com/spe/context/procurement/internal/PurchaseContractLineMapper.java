package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 合同行 Mapper。 */
@Mapper
public interface PurchaseContractLineMapper extends BaseMapper<PurchaseContractLine> {

  String COLUMNS =
      "id, contract_id, line_no, item_id, uom, price, min_qty, max_qty, tax_included,"
          + " valid_from, valid_to";

  @Select(
      "SELECT " + COLUMNS + " FROM purchase_contract_line WHERE contract_id = #{contractId}"
          + " ORDER BY line_no")
  List<PurchaseContractLine> findByContractId(@Param("contractId") long contractId);

  @Select("SELECT " + COLUMNS + " FROM purchase_contract_line WHERE id = #{id}")
  Optional<PurchaseContractLine> findById(@Param("id") long id);
}
