package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 定标 Mapper。 */
@Mapper
public interface SourcingAwardMapper extends BaseMapper<SourcingAward> {

  String COLUMNS =
      "id, award_no, rfq_id, supplier_id, quote_id, award_amount, currency, status, reason,"
          + " approved_by, approved_at, version, created_by, created_at";

  @Select("SELECT " + COLUMNS + " FROM sourcing_award WHERE id = #{id}")
  Optional<SourcingAward> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sourcing_award WHERE id = #{id} FOR UPDATE")
  Optional<SourcingAward> lockById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sourcing_award WHERE rfq_id = #{rfqId} ORDER BY id")
  List<SourcingAward> findByRfqId(@Param("rfqId") long rfqId);

  @Update(
      """
      UPDATE sourcing_award
      SET status = 'APPROVED', approved_by = #{operator}, approved_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markApproved(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sourcing_award SET status = 'PO_CREATED', version = version + 1
      WHERE id = #{id} AND status = 'APPROVED'
      """)
  int markPoCreated(@Param("id") long id);

  @Update(
      """
      UPDATE sourcing_award SET status = 'CANCELED', version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','APPROVED')
      """)
  int markCanceled(@Param("id") long id);
}
