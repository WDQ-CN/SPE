package com.spe.context.system.internal.audit;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 操作日志查询（SM5；M5 W5）：`audit_log` 只增不删，这里全部是只读 SQL。
 *
 * <p>索引按 EXPLAIN 实测决定（见 `22-w5-explain.log`）：默认序 `created_at DESC, id DESC`；
 * 动作过滤走 `(action, created_at)`。操作人用**姓名包含匹配**（`LIKE` 由 Service 转义 `%`/`_`）。
 */
@Mapper
public interface AuditLogReadMapper {

  @Select(
      """
      <script>
      SELECT id, operator_id, operator_name, action, target_type, target_id, detail, ip, created_at
      FROM audit_log
      <where>
        <if test="action != null"> AND action = #{action} </if>
        <if test="targetType != null"> AND target_type = #{targetType} </if>
        <if test="operator != null and operator != ''">
          AND operator_name LIKE CONCAT('%', #{operator}, '%')
        </if>
        <if test="from != null"> AND created_at &gt;= #{from} </if>
        <if test="to != null"> AND created_at &lt; #{to} </if>
      </where>
      ORDER BY created_at DESC, id DESC
      LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<AuditLogRow> findPage(
      @Param("action") String action,
      @Param("targetType") String targetType,
      @Param("operator") String operator,
      @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>
      SELECT COUNT(*) FROM audit_log
      <where>
        <if test="action != null"> AND action = #{action} </if>
        <if test="targetType != null"> AND target_type = #{targetType} </if>
        <if test="operator != null and operator != ''">
          AND operator_name LIKE CONCAT('%', #{operator}, '%')
        </if>
        <if test="from != null"> AND created_at &gt;= #{from} </if>
        <if test="to != null"> AND created_at &lt; #{to} </if>
      </where>
      </script>
      """)
  long count(
      @Param("action") String action,
      @Param("targetType") String targetType,
      @Param("operator") String operator,
      @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);

  /** 筛选项来源：动作码不写死在前端（避免词表漂移）。 */
  @Select("SELECT DISTINCT action FROM audit_log ORDER BY action")
  List<String> findActions();
}
