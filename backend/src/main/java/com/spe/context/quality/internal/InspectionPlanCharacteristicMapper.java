package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 检验特性 Mapper。 */
@Mapper
public interface InspectionPlanCharacteristicMapper extends BaseMapper<InspectionPlanCharacteristic> {

  String COLUMNS = "id, plan_id, line_no, name, spec, critical, method";

  @Select(
      "SELECT " + COLUMNS + " FROM inspection_plan_characteristic WHERE plan_id = #{planId}"
          + " ORDER BY line_no")
  List<InspectionPlanCharacteristic> findByPlanId(@Param("planId") long planId);
}
