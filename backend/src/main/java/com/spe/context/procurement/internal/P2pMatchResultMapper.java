package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 三单匹配结果 Mapper。 */
@Mapper
public interface P2pMatchResultMapper extends BaseMapper<P2pMatchResult> {

  String COLUMNS = "id, invoice_id, status, price_variance, qty_variance, amount_variance, detail";

  @Select("SELECT " + COLUMNS + " FROM p2p_match_result WHERE invoice_id = #{invoiceId}")
  Optional<P2pMatchResult> findByInvoiceId(@Param("invoiceId") long invoiceId);

  @Update(
      """
      UPDATE p2p_match_result
      SET status = #{result.status}, price_variance = #{result.priceVariance},
          qty_variance = #{result.qtyVariance}, amount_variance = #{result.amountVariance},
          detail = #{result.detail}
      WHERE invoice_id = #{result.invoiceId}
      """)
  int updateByInvoiceId(@Param("result") P2pMatchResult result);
}
