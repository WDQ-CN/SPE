package com.spe.context.system.internal.setting;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** `sys_setting` 的写 Mapper（属主上下文内可见，05 §8.3）；显式列名，不用 `SELECT *`（06 §4）。 */
@Mapper
public interface SysSettingMapper {

  @Select("SELECT id, setting_key, setting_value FROM sys_setting WHERE setting_key = #{key}")
  Optional<SysSetting> findByKey(@Param("key") String key);

  /**
   * 按键 upsert：`setting_key` 上有唯一键，首次保存是 INSERT、之后是 UPDATE。
   *
   * <p>用一条语句而不是「先查再决定插/改」，避免两个管理员同时保存时撞唯一键。
   * 行别名写法（`AS new`）是 MySQL 8.0.19+ 的现行语法，替代 8.4 已弃用的 `VALUES()` 函数。
   */
  @Insert(
      """
      <script>
      INSERT INTO sys_setting (setting_key, setting_value)
      VALUES (#{key}, #{value}) AS new
      ON DUPLICATE KEY UPDATE setting_value = new.setting_value
      </script>
      """)
  int upsert(@Param("key") String key, @Param("value") String value);
}
