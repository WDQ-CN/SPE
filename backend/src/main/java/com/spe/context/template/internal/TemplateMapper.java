package com.spe.context.template.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `template` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p>列表用**一条 JOIN + GROUP BY** 取回全部展示字段（12 §R-15）：模板页要显示"版本数 + 最新已发布版本 +
 * 是否有草稿"，逐行回查就是 10 行 11 条 SQL。
 *
 * <p>列别名统一写**驼峰**，不依赖 `map-underscore-to-camel-case` 的全局开关——那个映射行为没有显式约定，
 * 写死别名才不会因为将来动配置而静默错位。
 */
@Mapper
public interface TemplateMapper extends BaseMapper<Template> {

  @Select(
      """
      SELECT t.id                AS id,
             t.name              AS name,
             COUNT(v.id)         AS versionCount,
             COALESCE(SUM(v.status = 'DRAFT'), 0) AS draftCount,
             MAX(CASE WHEN v.status = 'PUBLISHED' THEN v.version_no END) AS latestPublishedNo,
             SUBSTRING_INDEX(GROUP_CONCAT(v.id ORDER BY v.version_no DESC), ',', 1) AS latestVersionId
      FROM template t
      LEFT JOIN template_version v ON v.template_id = t.id
      GROUP BY t.id, t.name
      ORDER BY t.id
      LIMIT #{size} OFFSET #{offset}
      """)
  List<TemplateRow> findPage(@Param("offset") long offset, @Param("size") int size);

  @Select("SELECT COUNT(*) FROM template")
  long countAll();

  @Select("SELECT id, name FROM template WHERE id = #{id}")
  Optional<Template> findById(@Param("id") long id);

  @Update("UPDATE template SET name = #{name} WHERE id = #{id}")
  int updateName(@Param("id") long id, @Param("name") String name);

  // ⚠️ 这里原先有一把 `lockById`（`SELECT id FROM template WHERE id = ? FOR UPDATE`），**2026-09-27 删除**
  // （04-080）：它的目的是"串行化同一模板下的复制"，但实测**它是冗余的** ——
  // `TemplateVersionMapper.findMaxVersionNoForUpdate`（`SELECT MAX(version_no) ... FOR UPDATE`）已经挡在
  // 同一个位置（另一个复制方要读的正是那一行/那段间隙）：① 只删本方法、并发用例仍绿；② 只把 max 那条降级
  // 成快照、用例也绿；③ **两者同时动才红**。两把锁互相掩盖了对方的失效 ⇒ 用例对**单独一把**失效无感。
  // 删掉冗余这把之后，max 那条成为唯一的串行化点，"那一把一坏就红"。
  //
  // 连带失去的一点（可忽略、但如实记下）：原先它顺带校验了"模板行存在"（不存在 → 2009）。现在这条校验没有
  // 了 —— 但 `template_version.template_id` 只能由本上下文的写路径写入，版本行存在即意味着模板行当时存在；
  // 且 V7 有意不建外键（06 §4），这从来就不是硬保证。
}
