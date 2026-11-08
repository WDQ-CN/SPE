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

/** 业务事件到会计科目的映射 Mapper。 */
@Mapper
public interface AccountMappingMapper extends BaseMapper<AccountMapping> {

  String COLUMNS =
      "id, company_id, event_type, dimension, debit_account_id, credit_account_id,"
          + " tax_account_id, valid_from, status, version";

  @Select(
      """
      <script>SELECT id, company_id, event_type, dimension, debit_account_id, credit_account_id,
      tax_account_id, valid_from, status, version
      FROM account_mapping
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="eventType != null and eventType != ''">AND event_type = #{eventType}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      ORDER BY company_id, event_type, dimension, valid_from
      LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<AccountMapping> findPage(
      @Param("companyId") Long companyId,
      @Param("eventType") String eventType,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM account_mapping
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="eventType != null and eventType != ''">AND event_type = #{eventType}</if>
        <if test="status != null">AND status = #{status}</if>
      </where></script>
      """)
  long countBy(
      @Param("companyId") Long companyId,
      @Param("eventType") String eventType,
      @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM account_mapping WHERE id = #{id}")
  Optional<AccountMapping> findById(@Param("id") long id);

  @Select(
      """
      SELECT id, company_id, event_type, dimension, debit_account_id, credit_account_id,
             tax_account_id, valid_from, status, version
      FROM account_mapping
      WHERE company_id = #{companyId} AND event_type = #{eventType} AND status = 'ACTIVE'
        AND dimension = '' AND valid_from <= #{date}
      ORDER BY valid_from DESC, id DESC
      LIMIT 1
      """)
  Optional<AccountMapping> findEffective(
      @Param("companyId") long companyId,
      @Param("eventType") String eventType,
      @Param("date") LocalDate date);

  @Select(
      "SELECT COUNT(*) FROM account_mapping WHERE company_id = #{companyId}"
          + " AND event_type = #{eventType} AND dimension = #{dimension} AND valid_from = #{validFrom}")
  long countByKey(
      @Param("companyId") long companyId,
      @Param("eventType") String eventType,
      @Param("dimension") String dimension,
      @Param("validFrom") LocalDate validFrom);

  @Update(
      """
      UPDATE account_mapping
      SET event_type = #{eventType}, dimension = #{dimension}, debit_account_id = #{debitAccountId},
          credit_account_id = #{creditAccountId}, tax_account_id = #{taxAccountId},
          valid_from = #{validFrom}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("eventType") String eventType,
      @Param("dimension") String dimension,
      @Param("debitAccountId") long debitAccountId,
      @Param("creditAccountId") long creditAccountId,
      @Param("taxAccountId") Long taxAccountId,
      @Param("validFrom") LocalDate validFrom,
      @Param("version") int version);

  @Update(
      """
      UPDATE account_mapping SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
