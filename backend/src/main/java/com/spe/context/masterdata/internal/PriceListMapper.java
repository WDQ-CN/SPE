package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 价格表 Mapper。 */
@Mapper
public interface PriceListMapper extends BaseMapper<PriceList> {

  String COLUMNS = "id, code, list_type, currency, valid_from, valid_to, status, version";

  @Select(
      """
      <script>SELECT id, code, list_type, currency, valid_from, valid_to, status, version
      FROM price_list
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      ORDER BY code LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<PriceList> findPage(
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM price_list
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where></script>
      """)
  long countBy(@Param("keyword") String keyword, @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM price_list WHERE id = #{id}")
  Optional<PriceList> findById(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM price_list WHERE code = #{code}")
  long countByCode(@Param("code") String code);

  @Update(
      """
      UPDATE price_list
      SET code = #{code}, currency = #{currency}, valid_from = #{validFrom}, valid_to = #{validTo},
          version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("currency") String currency,
      @Param("validFrom") LocalDate validFrom,
      @Param("validTo") LocalDate validTo,
      @Param("version") int version);

  @Update(
      """
      UPDATE price_list SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
