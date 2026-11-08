package com.spe.context.reporting.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 报表定义 Mapper。 */
@Mapper
public interface ReportDefinitionMapper extends BaseMapper<ReportDefinition> {

  String COLUMNS = "id, code, name, query_key, description, status, created_at";

  @Select("SELECT " + COLUMNS + " FROM report_definition ORDER BY id")
  List<ReportDefinition> findAll();

  @Select("SELECT " + COLUMNS + " FROM report_definition WHERE code = #{code}")
  Optional<ReportDefinition> findByCode(@Param("code") String code);

  @Select("SELECT " + COLUMNS + " FROM report_definition WHERE id = #{id}")
  Optional<ReportDefinition> findById(@Param("id") long id);
}
