package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** RFQ Mapper。 */
@Mapper
public interface SourcingRfqMapper extends BaseMapper<SourcingRfq> {

  String COLUMNS =
      "id, rfq_no, company_id, plant_id, buyer_id, title, currency, deadline_at, sealed, status,"
          + " source_pr_id, award_policy, version, created_by, created_at, sent_at, closed_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="keyword != null and keyword != ''">AND title LIKE CONCAT('%', #{keyword}, '%')</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sourcing_rfq " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SourcingRfq> findPage(
      @Param("companyId") Long companyId,
      @Param("status") SourcingRfqStatus status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM sourcing_rfq " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("status") SourcingRfqStatus status,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM sourcing_rfq WHERE id = #{id}")
  Optional<SourcingRfq> findById(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sourcing_rfq WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  java.util.List<SourcingRfq> findByIds(@Param("ids") java.util.Collection<Long> ids);

  @Select("SELECT " + COLUMNS + " FROM sourcing_rfq WHERE id = #{id} FOR UPDATE")
  Optional<SourcingRfq> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE sourcing_rfq SET status = 'SENT', sent_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sourcing_rfq SET status = 'CLOSED', closed_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'SENT'
      """)
  int markClosed(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sourcing_rfq SET status = 'AWARDED', version = version + 1
      WHERE id = #{id} AND status IN ('CLOSED','EVALUATING')
      """)
  int markAwarded(@Param("id") long id);

  @Update(
      """
      UPDATE sourcing_rfq SET status = 'CANCELED', version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','SENT','CLOSED','EVALUATING')
      """)
  int markCanceled(@Param("id") long id);
}
