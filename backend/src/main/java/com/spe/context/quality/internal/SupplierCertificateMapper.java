package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 供应商证书 Mapper。 */
@Mapper
public interface SupplierCertificateMapper extends BaseMapper<SupplierCertificate> {

  String COLUMNS =
      "id, company_id, supplier_id, item_id, cert_type, cert_no, valid_from, valid_to, status,"
          + " created_by, created_at";

  @Select("SELECT " + COLUMNS + " FROM supplier_certificate WHERE id = #{id}")
  Optional<SupplierCertificate> findById(@Param("id") long id);

  @Select(
      """
      <script>SELECT id, company_id, supplier_id, item_id, cert_type, cert_no, valid_from,
                     valid_to, status, created_by, created_at
      FROM supplier_certificate
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="supplierId != null">AND supplier_id = #{supplierId}</if>
      </where>
      ORDER BY valid_to, id LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<SupplierCertificate> findPage(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM supplier_certificate
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="supplierId != null">AND supplier_id = #{supplierId}</if>
      </where></script>
      """)
  long countBy(
      @Param("companyId") Long companyId, @Param("supplierId") Long supplierId);

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_certificate"
          + " WHERE company_id = #{companyId} AND status = 'VALID' AND valid_to <= #{cutoff}"
          + " ORDER BY valid_to, id")
  List<SupplierCertificate> findExpiring(
      @Param("companyId") long companyId, @Param("cutoff") LocalDate cutoff);
}
