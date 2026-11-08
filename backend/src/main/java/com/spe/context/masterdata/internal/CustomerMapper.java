package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 客户主数据 Mapper。 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {

  String COLUMNS =
      "id, code, name, tax_no, address, contact_name, contact_phone, currency, payment_terms,"
          + " credit_limit, credit_used, status, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM customer " + SEARCH_CONDITION
          + " ORDER BY code LIMIT #{size} OFFSET #{offset}</script>")
  List<Customer> findPage(
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM customer " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("keyword") String keyword, @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM customer WHERE id = #{id}")
  Optional<Customer> findById(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM customer WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + " ORDER BY id</script>")
  List<Customer> findByIds(@Param("ids") java.util.Collection<Long> ids);

  @Select("SELECT COUNT(*) FROM customer WHERE code = #{code}")
  long countByCode(@Param("code") String code);

  @Update(
      """
      UPDATE customer
      SET credit_used = COALESCE(credit_used, 0) + #{amount}, version = version + 1
      WHERE id = #{id} AND status = 'ACTIVE'
        AND COALESCE(credit_used, 0) + #{amount} <= COALESCE(credit_limit, 0)
      """)
  int increaseCreditUsed(@Param("id") long id, @Param("amount") BigDecimal amount);

  @Update(
      """
      UPDATE customer
      SET credit_used = GREATEST(COALESCE(credit_used, 0) - #{amount}, 0),
          version = version + 1
      WHERE id = #{id}
      """)
  int decreaseCreditUsed(@Param("id") long id, @Param("amount") BigDecimal amount);

  @Update(
      """
      UPDATE customer
      SET code = #{code}, name = #{name}, tax_no = #{taxNo}, address = #{address},
          contact_name = #{contactName}, contact_phone = #{contactPhone}, currency = #{currency},
          payment_terms = #{paymentTerms}, credit_limit = #{creditLimit}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("taxNo") String taxNo,
      @Param("address") String address,
      @Param("contactName") String contactName,
      @Param("contactPhone") String contactPhone,
      @Param("currency") String currency,
      @Param("paymentTerms") String paymentTerms,
      @Param("creditLimit") BigDecimal creditLimit,
      @Param("version") int version);

  @Update(
      """
      UPDATE customer SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
