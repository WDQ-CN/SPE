package com.spe.context.supplier.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 门户邀请 Mapper。 */
@Mapper
public interface SupplierPortalInvitationMapper extends BaseMapper<SupplierPortalInvitation> {

  String COLUMNS =
      "id, supplier_id, token_hash, status, expires_at, created_by, created_at, accepted_at,"
          + " portal_user_id";

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_portal_invitation"
          + " WHERE token_hash = #{tokenHash} AND status = 'PENDING' FOR UPDATE")
  Optional<SupplierPortalInvitation> lockPendingByHash(@Param("tokenHash") String tokenHash);

  @Update(
      """
      UPDATE supplier_portal_invitation
      SET status = 'ACCEPTED', accepted_at = #{now}, portal_user_id = #{portalUserId}
      WHERE id = #{id} AND status = 'PENDING'
      """)
  int markAccepted(
      @Param("id") long id,
      @Param("portalUserId") long portalUserId,
      @Param("now") LocalDateTime now);
}
