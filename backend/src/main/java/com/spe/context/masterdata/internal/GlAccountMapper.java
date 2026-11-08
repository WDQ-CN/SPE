package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.AccountType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.NormalBalance;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 会计科目 Mapper。 */
@Mapper
public interface GlAccountMapper extends BaseMapper<GlAccount> {

  String COLUMNS =
      "id, company_id, code, name, account_type, normal_balance, requires_cost_center, status, version";

  @Select(
      """
      <script>SELECT id, company_id, code, name, account_type, normal_balance,
      requires_cost_center, status, version
      FROM gl_account
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      ORDER BY company_id, code LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<GlAccount> findPage(
      @Param("companyId") Long companyId,
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM gl_account
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where></script>
      """)
  long countBy(
      @Param("companyId") Long companyId,
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM gl_account WHERE id = #{id}")
  Optional<GlAccount> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM gl_account WHERE id = #{id} AND company_id = #{companyId}")
  Optional<GlAccount> findByIdAndCompany(@Param("id") long id, @Param("companyId") long companyId);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM gl_account WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<GlAccount> findByIds(@Param("ids") java.util.Collection<Long> ids);

  @Select("SELECT COUNT(*) FROM gl_account WHERE company_id = #{companyId} AND code = #{code}")
  long countByCompanyAndCode(@Param("companyId") long companyId, @Param("code") String code);

  @Update(
      """
      UPDATE gl_account
      SET code = #{code}, name = #{name}, account_type = #{accountType},
          normal_balance = #{normalBalance}, requires_cost_center = #{requiresCostCenter},
          version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("accountType") AccountType accountType,
      @Param("normalBalance") NormalBalance normalBalance,
      @Param("requiresCostCenter") boolean requiresCostCenter,
      @Param("version") int version);

  @Update(
      """
      UPDATE gl_account SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}
