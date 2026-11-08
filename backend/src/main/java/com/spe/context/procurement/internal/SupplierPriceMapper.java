package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 供应商价格库 Mapper。 */
@Mapper
public interface SupplierPriceMapper extends BaseMapper<SupplierPrice> {

  String COLUMNS =
      "id, company_id, supplier_id, item_id, uom, min_qty, price, tax_included, currency,"
          + " valid_from, valid_to, source_type, contract_id, award_id, status, version";

  @Select(
      """
      <script>
      SELECT id, company_id, supplier_id, item_id, uom, min_qty, price, tax_included, currency,
             valid_from, valid_to, source_type, contract_id, award_id, status, version
      FROM supplier_price
      WHERE supplier_id = #{supplierId} AND status = 'ACTIVE'
        AND valid_from &lt;= #{date} AND (valid_to IS NULL OR valid_to &gt;= #{date})
        <if test="itemId != null">AND item_id = #{itemId}</if>
      ORDER BY CASE source_type WHEN 'CONTRACT' THEN 0 WHEN 'AWARD' THEN 1 ELSE 2 END,
               valid_from DESC, min_qty DESC, id DESC
      </script>
      """)
  List<SupplierPrice> findEffective(
      @Param("supplierId") long supplierId,
      @Param("itemId") Long itemId,
      @Param("date") LocalDate date);

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_price WHERE company_id = #{companyId}"
          + " AND supplier_id = #{supplierId} AND item_id = #{itemId} AND uom = #{uom}"
          + " AND min_qty = #{minQty} AND valid_from = #{validFrom}")
  java.util.Optional<SupplierPrice> findByKey(
      @Param("companyId") long companyId,
      @Param("supplierId") long supplierId,
      @Param("itemId") long itemId,
      @Param("uom") String uom,
      @Param("minQty") BigDecimal minQty,
      @Param("validFrom") LocalDate validFrom);

  @Insert(
      """
      INSERT INTO supplier_price
        (company_id, supplier_id, item_id, uom, min_qty, price, tax_included, currency,
         valid_from, valid_to, source_type, contract_id, award_id, status, version)
      VALUES
        (#{companyId}, #{supplierId}, #{itemId}, #{uom}, #{minQty}, #{price}, #{taxIncluded},
         #{currency}, #{validFrom}, #{validTo}, #{sourceType}, #{contractId}, #{awardId},
         'ACTIVE', 0)
      ON DUPLICATE KEY UPDATE
        price = VALUES(price), tax_included = VALUES(tax_included), currency = VALUES(currency),
        valid_to = VALUES(valid_to), source_type = VALUES(source_type),
        contract_id = COALESCE(VALUES(contract_id), contract_id),
        award_id = COALESCE(VALUES(award_id), award_id),
        status = 'ACTIVE', version = version + 1
      """)
  int upsert(
      @Param("companyId") long companyId,
      @Param("supplierId") long supplierId,
      @Param("itemId") long itemId,
      @Param("uom") String uom,
      @Param("minQty") BigDecimal minQty,
      @Param("price") BigDecimal price,
      @Param("taxIncluded") boolean taxIncluded,
      @Param("currency") String currency,
      @Param("validFrom") LocalDate validFrom,
      @Param("validTo") LocalDate validTo,
      @Param("sourceType") SupplierPriceSource sourceType,
      @Param("contractId") Long contractId,
      @Param("awardId") Long awardId);

  @Update(
      "UPDATE supplier_price SET status = 'INACTIVE', version = version + 1"
          + " WHERE contract_id = #{contractId} AND status = 'ACTIVE'")
  int deactivateByContract(@Param("contractId") long contractId);
}
