package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 价格表行 Mapper。 */
@Mapper
public interface PriceLineMapper extends BaseMapper<PriceLine> {

  String COLUMNS =
      "id, price_list_id, item_id, uom, min_qty, price, tax_included, valid_from, valid_to, version";

  @Select("SELECT " + COLUMNS + " FROM price_line WHERE price_list_id = #{priceListId}"
      + " ORDER BY item_id, min_qty, valid_from")
  List<PriceLine> findByList(@Param("priceListId") long priceListId);

  @Select("SELECT " + COLUMNS + " FROM price_line WHERE id = #{id} AND price_list_id = #{priceListId}")
  Optional<PriceLine> findByIdAndList(@Param("id") long id, @Param("priceListId") long priceListId);

  /**
   * 有效采购价：价格表与行均在生效期内、同币种、数量阶梯命中，取阶梯最高、生效日最近的一条。
   *
   * <p>没有命中返回 null——调用方据此区分「没有价目表」与「价格为 0」。
   */
  @Select(
      """
      SELECT pl.price
      FROM price_line pl
      JOIN price_list p ON p.id = pl.price_list_id
      WHERE p.list_type = 'PURCHASE' AND p.status = 'ACTIVE'
        AND p.currency = #{currency}
        AND p.valid_from <= #{date} AND (p.valid_to IS NULL OR p.valid_to >= #{date})
        AND pl.item_id = #{itemId} AND pl.uom = #{uom}
        AND pl.min_qty <= #{qty}
        AND pl.valid_from <= #{date} AND (pl.valid_to IS NULL OR pl.valid_to >= #{date})
      ORDER BY pl.min_qty DESC, pl.valid_from DESC, pl.id DESC
      LIMIT 1
      """)
  BigDecimal findEffectivePurchasePrice(
      @Param("itemId") long itemId,
      @Param("uom") String uom,
      @Param("qty") BigDecimal qty,
      @Param("currency") String currency,
      @Param("date") LocalDate date);

  @Select(
      "SELECT COUNT(*) FROM price_line WHERE price_list_id = #{priceListId}"
          + " AND item_id = #{itemId} AND uom = #{uom} AND min_qty = #{minQty}"
          + " AND valid_from = #{validFrom}")
  long countByKey(
      @Param("priceListId") long priceListId,
      @Param("itemId") long itemId,
      @Param("uom") String uom,
      @Param("minQty") BigDecimal minQty,
      @Param("validFrom") LocalDate validFrom);

  @Delete("DELETE FROM price_line WHERE id = #{id} AND price_list_id = #{priceListId}")
  int deleteByIdAndList(@Param("id") long id, @Param("priceListId") long priceListId);
}
