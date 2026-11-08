package com.spe.context.reporting.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 报表运行 Mapper。 */
@Mapper
public interface ReportRunMapper extends BaseMapper<ReportRun> {

  String COLUMNS =
      "id, definition_id, company_id, as_of, status, row_count, result_json, error_message,"
          + " duration_ms, requested_by, created_at";

  @Select("SELECT " + COLUMNS + " FROM report_run WHERE id = #{id}")
  Optional<ReportRun> findById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM report_run WHERE definition_id = #{definitionId}"
          + " ORDER BY id DESC LIMIT #{size}")
  List<ReportRun> findRecent(@Param("definitionId") long definitionId, @Param("size") int size);

  /** 列顺序必须与 {@link ReportRunView} 的声明顺序一致：MyBatis 按位置构造 record。 */
  @Select(
      """
      <script>
      SELECT r.id, r.definition_id, d.code, d.name, r.company_id, r.as_of, r.status, r.row_count,
             r.duration_ms, r.created_at
      FROM report_run r JOIN report_definition d ON d.id = r.definition_id
      <where>
        <if test="companyId != null">r.company_id = #{companyId}</if>
      </where>
      ORDER BY r.id DESC LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<ReportRunView> findPage(
      @Param("companyId") Long companyId, @Param("offset") long offset, @Param("size") int size);

  @Select(
      """
      <script>
      SELECT COUNT(*) FROM report_run
      <where>
        <if test="companyId != null">company_id = #{companyId}</if>
      </where>
      </script>
      """)
  long countBy(@Param("companyId") Long companyId);
}
