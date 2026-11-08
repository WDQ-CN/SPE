package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 供应商扩展 Mapper（1:1 supplier）。 */
@Mapper
public interface SupplierExtensionMapper extends BaseMapper<SupplierExtension> {

  String COLUMNS =
      "supplier_id, tax_no, bank_account, bank_name, payment_terms, currency, incoterms,"
          + " qualification_status, certificate_expiry, version";

  @Select("SELECT " + COLUMNS + " FROM supplier_extension WHERE supplier_id = #{supplierId}")
  Optional<SupplierExtension> findById(@Param("supplierId") long supplierId);

  @Update(
      """
      UPDATE supplier_extension
      SET tax_no = #{taxNo}, bank_account = #{bankAccount}, bank_name = #{bankName},
          payment_terms = #{paymentTerms}, currency = #{currency}, incoterms = #{incoterms},
          qualification_status = #{qualificationStatus}, certificate_expiry = #{certificateExpiry},
          version = version + 1
      WHERE supplier_id = #{supplierId} AND version = #{version}
      """)
  int updateProfile(
      @Param("supplierId") long supplierId,
      @Param("taxNo") String taxNo,
      @Param("bankAccount") String bankAccount,
      @Param("bankName") String bankName,
      @Param("paymentTerms") String paymentTerms,
      @Param("currency") String currency,
      @Param("incoterms") String incoterms,
      @Param("qualificationStatus") com.spe.context.masterdata.api.QualificationStatus qualificationStatus,
      @Param("certificateExpiry") java.time.LocalDate certificateExpiry,
      @Param("version") int version);
}
