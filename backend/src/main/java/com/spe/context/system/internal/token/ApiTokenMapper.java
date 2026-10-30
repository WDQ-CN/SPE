package com.spe.context.system.internal.token;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `api_token` 的 Mapper（M6）。列表显式 LIMIT/OFFSET，不引分页拦截器；
 * 停用与 last_used 都是守卫 UPDATE。
 */
@Mapper
public interface ApiTokenMapper extends BaseMapper<ApiToken> {

  String COLUMNS =
      "id, name, token_hash, token_prefix, status, last_used_at, created_by,"
          + " created_at, updated_at, version";

  @Select(
      "SELECT " + COLUMNS + " FROM api_token ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<ApiToken> findPage(@Param("offset") long offset, @Param("size") int size);

  @Select("SELECT COUNT(*) FROM api_token")
  long countAll();

  @Select("SELECT " + COLUMNS + " FROM api_token WHERE id = #{id}")
  Optional<ApiToken> findById(@Param("id") long id);

  /** 鉴权按 SHA-256 哈希唯一查找；状态由 Service 再判。 */
  @Select("SELECT " + COLUMNS + " FROM api_token WHERE token_hash = #{tokenHash}")
  Optional<ApiToken> findByHash(@Param("tokenHash") String tokenHash);

  /** 停用守卫：前置 ENABLED + version；0 行由 Service 复读区分 3001/2036。 */
  @Update(
      "UPDATE api_token SET status = 'DISABLED', version = version + 1"
          + " WHERE id = #{id} AND status = 'ENABLED' AND version = #{version}")
  int disable(@Param("id") long id, @Param("version") int version);

  /** 鉴权成功后touch；若期间被停用，0 行 ⇒ 本次认证作废。 */
  @Update(
      "UPDATE api_token SET last_used_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND status = 'ENABLED'")
  int touchLastUsed(@Param("id") long id);
}
