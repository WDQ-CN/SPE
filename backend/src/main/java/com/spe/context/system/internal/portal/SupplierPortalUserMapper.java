package com.spe.context.system.internal.portal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 供应商门户账号 Mapper。 */
@Mapper
public interface SupplierPortalUserMapper extends BaseMapper<SupplierPortalUser> {

  String COLUMNS =
      "id, supplier_id, login_name, password_hash, status, failed_attempts, locked_until,"
          + " last_login_at, version, created_at";

  @Select("SELECT " + COLUMNS + " FROM supplier_portal_user WHERE login_name = #{loginName}")
  Optional<SupplierPortalUser> findByLoginNameForAuth(@Param("loginName") String loginName);

  @Select("SELECT " + COLUMNS + " FROM supplier_portal_user WHERE id = #{id}")
  Optional<SupplierPortalUser> findById(@Param("id") long id);

  @Update(
      "UPDATE supplier_portal_user SET last_login_at = CURRENT_TIMESTAMP(3), version = version + 1"
          + " WHERE id = #{id}")
  int updateLastLogin(@Param("id") long id);
}
