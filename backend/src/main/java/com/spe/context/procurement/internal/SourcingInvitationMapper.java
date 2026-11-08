package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 询价邀请 Mapper。 */
@Mapper
public interface SourcingInvitationMapper extends BaseMapper<SourcingInvitation> {

  String COLUMNS = "id, rfq_id, supplier_id, status, invited_at, viewed_at, responded_at";

  @Select(
      "SELECT " + COLUMNS + " FROM sourcing_invitation WHERE rfq_id = #{rfqId} ORDER BY id")
  List<SourcingInvitation> findByRfqId(@Param("rfqId") long rfqId);

  @Select(
      "SELECT " + COLUMNS + " FROM sourcing_invitation"
          + " WHERE rfq_id = #{rfqId} AND supplier_id = #{supplierId}")
  Optional<SourcingInvitation> findByRfqAndSupplier(
      @Param("rfqId") long rfqId, @Param("supplierId") long supplierId);

  @Select(
      "SELECT " + COLUMNS + " FROM sourcing_invitation WHERE supplier_id = #{supplierId}"
          + " ORDER BY id DESC")
  List<SourcingInvitation> findBySupplier(@Param("supplierId") long supplierId);

  @Update(
      """
      UPDATE sourcing_invitation SET status = 'VIEWED', viewed_at = NOW(3)
      WHERE id = #{id} AND status = 'INVITED'
      """)
  int markViewed(@Param("id") long id);

  @Update(
      """
      UPDATE sourcing_invitation SET status = 'QUOTED', responded_at = NOW(3)
      WHERE id = #{id} AND status IN ('INVITED','VIEWED')
      """)
  int markQuoted(@Param("id") long id);
}
