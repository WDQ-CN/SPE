package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 税码 Mapper。 */
@Mapper
public interface TaxCodeMapper extends BaseMapper<TaxCode> {

  String COLUMNS = "id, code, country, rate, tax_type, effective_from, status, version";

  @Select(
      """
      <script>SELECT id, code, country, rate, tax_type, effective_from, status, version
      FROM tax_code
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      ORDER BY code LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<TaxCode> findPage(
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM tax_code
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where></script>
      """)
  long countBy(@Param("keyword") String keyword, @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM tax_code WHERE id = #{id}")
  Optional<TaxCode> findById(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM tax_code WHERE code = #{code}")
  long countByCode(@Param("code") String code);

  @Update(
      """
      UPDATE tax_code
      SET code = #{code}, country = #{country}, rate = #{rate}, tax_type = #{taxType},
          effective_from = #{effectiveFrom}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("country") String country,
      @Param("rate") BigDecimal rate,
      @Param("taxType") com.spe.context.masterdata.api.TaxType taxType,
      @Param("effectiveFrom") LocalDate effectiveFrom,
      @Param("version") int version);

  @Update(
      """
      UPDATE tax_code SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
