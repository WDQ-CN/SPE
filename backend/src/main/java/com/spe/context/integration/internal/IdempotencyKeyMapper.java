package com.spe.context.integration.internal;

import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 幂等键 Mapper。 */
@Mapper
public interface IdempotencyKeyMapper {

  String COLUMNS =
      "scope, idem_key, request_hash, response_status, response_body, created_at, expires_at";

  @Select(
      "SELECT " + COLUMNS + " FROM idempotency_key"
          + " WHERE scope = #{scope} AND idem_key = #{idemKey}")
  Optional<IdempotencyRecord> find(
      @Param("scope") String scope, @Param("idemKey") String idemKey);

  @Insert(
      """
      INSERT INTO idempotency_key
        (scope, idem_key, request_hash, response_status, response_body, expires_at)
      VALUES (#{scope}, #{idemKey}, #{requestHash}, 0, NULL, #{expiresAt})
      """)
  int reserve(
      @Param("scope") String scope,
      @Param("idemKey") String idemKey,
      @Param("requestHash") String requestHash,
      @Param("expiresAt") LocalDateTime expiresAt);

  @Update(
      """
      UPDATE idempotency_key SET response_status = #{status}, response_body = #{body}
      WHERE scope = #{scope} AND idem_key = #{idemKey} AND response_status = 0
      """)
  int complete(
      @Param("scope") String scope,
      @Param("idemKey") String idemKey,
      @Param("status") int status,
      @Param("body") String body);

  @Delete("DELETE FROM idempotency_key WHERE scope = #{scope} AND idem_key = #{idemKey}")
  int delete(@Param("scope") String scope, @Param("idemKey") String idemKey);
}
