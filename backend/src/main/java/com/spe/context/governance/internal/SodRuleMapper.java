package com.spe.context.governance.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** SoD 规则 Mapper。 */
@Mapper
public interface SodRuleMapper extends BaseMapper<SodRule> {

  String COLUMNS = "id, code, role_a, role_b, description, status, created_by, created_at";

  @Select("SELECT " + COLUMNS + " FROM sod_rule WHERE id = #{id}")
  Optional<SodRule> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sod_rule ORDER BY id")
  List<SodRule> findAll();

  @Select("SELECT " + COLUMNS + " FROM sod_rule WHERE status = 'ACTIVE' ORDER BY id")
  List<SodRule> findActive();
}
