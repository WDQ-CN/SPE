package com.spe.context.project.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 工时行 Mapper。 */
@Mapper
public interface TimesheetLineMapper extends BaseMapper<TimesheetLine> {

  String COLUMNS =
      "id, timesheet_id, line_no, project_id, wbs_id, work_date, hours, rate, amount";

  @Select(
      "SELECT " + COLUMNS + " FROM project_timesheet_line WHERE timesheet_id = #{timesheetId}"
          + " ORDER BY line_no")
  List<TimesheetLine> findByTimesheetId(@Param("timesheetId") long timesheetId);
}
