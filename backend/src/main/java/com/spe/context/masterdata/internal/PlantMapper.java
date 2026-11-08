package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工厂主数据 Mapper。 */
@Mapper
public interface PlantMapper extends BaseMapper<Plant> {

  String COLUMNS = "id, company_id, code, name, address, status, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM plant " + SEARCH_CONDITION
          + " ORDER BY company_id, code LIMIT #{size} OFFSET #{offset}</script>")
  List<Plant> findPage(
      @Param("companyId") Long companyId,
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM plant " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM plant WHERE id = #{id}")
  Optional<Plant> findById(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM plant WHERE company_id = #{companyId} AND code = #{code}")
  long countByCompanyAndCode(@Param("companyId") long companyId, @Param("code") String code);

  @Update(
      """
      UPDATE plant
      SET code = #{code}, name = #{name}, address = #{address}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("address") String address,
      @Param("version") int version);

  @Update(
      """
      UPDATE plant
      SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
