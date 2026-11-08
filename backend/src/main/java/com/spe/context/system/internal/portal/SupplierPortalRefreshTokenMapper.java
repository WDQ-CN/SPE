package com.spe.context.system.internal.portal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 门户刷新令牌 Mapper。 */
@Mapper
public interface SupplierPortalRefreshTokenMapper extends BaseMapper<SupplierPortalRefreshToken> {

  String COLUMNS =
      "id, portal_user_id, token_hash, expires_at, revoked_at, created_at";

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_portal_refresh_token"
          + " WHERE token_hash = #{tokenHash} AND revoked_at IS NULL"
          + " AND expires_at > CURRENT_TIMESTAMP(3)")
  Optional<SupplierPortalRefreshToken> findActiveByHash(@Param("tokenHash") String tokenHash);

  @Update(
      "UPDATE supplier_portal_refresh_token SET revoked_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND revoked_at IS NULL")
  int revoke(@Param("id") long id);
}
