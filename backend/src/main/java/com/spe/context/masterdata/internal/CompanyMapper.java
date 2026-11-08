package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 公司主数据 Mapper。列表/计数共用同一条件片段，避免 total 与行漂移。 */
@Mapper
public interface CompanyMapper extends BaseMapper<Company> {

  String COLUMNS = "id, code, name, base_currency, tax_no, country, status, version";

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
      "<script>SELECT " + COLUMNS + " FROM company " + SEARCH_CONDITION
          + " ORDER BY code LIMIT #{size} OFFSET #{offset}</script>")
  List<Company> findPage(
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM company " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("keyword") String keyword, @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM company WHERE id = #{id}")
  Optional<Company> findById(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM company WHERE code = #{code}")
  long countByCode(@Param("code") String code);

  @Update(
      """
      UPDATE company
      SET code = #{code}, name = #{name}, base_currency = #{baseCurrency},
          tax_no = #{taxNo}, country = #{country}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("baseCurrency") String baseCurrency,
      @Param("taxNo") String taxNo,
      @Param("country") String country,
      @Param("version") int version);

  @Update(
      """
      UPDATE company
      SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
