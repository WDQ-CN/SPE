package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 收款核销 Mapper。 */
@Mapper
public interface ReceiptAllocationMapper extends BaseMapper<ReceiptAllocation> {

  String COLUMNS = "id, receipt_id, invoice_id, amount";

  @Select(
      "SELECT " + COLUMNS + " FROM receipt_allocation WHERE receipt_id = #{receiptId}"
          + " ORDER BY id")
  List<ReceiptAllocation> findByReceiptId(@Param("receiptId") long receiptId);
}
