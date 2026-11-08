package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 检验结果 Mapper。 */
@Mapper
public interface InspectionResultMapper extends BaseMapper<InspectionResult> {

  String COLUMNS =
      "id, inspection_id, line_no, characteristic_id, name, measured_value, result, remark";

  @Select(
      "SELECT " + COLUMNS + " FROM inspection_result WHERE inspection_id = #{inspectionId}"
          + " ORDER BY line_no")
  List<InspectionResult> findByInspectionId(@Param("inspectionId") long inspectionId);
}
