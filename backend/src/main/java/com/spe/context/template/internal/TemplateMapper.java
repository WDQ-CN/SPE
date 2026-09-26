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

  /**
   * 可分页选用的模板：**只含至少有一个已发布版本的**（01 J1 要求任务绑定已发布版本）。
   *
   * <p>不能拿 {@link #findPage} 的结果再过滤——那是"先分页后过滤"，页大小会不足、`total` 也不对。
   * 条件必须进 SQL，且与 {@link #countPublishable} 一致。
   */
  @Select(
      """
      SELECT t.id AS id, t.name AS name, MAX(v.version_no) AS latestPublishedNo
      FROM template t
      JOIN template_version v ON v.template_id = t.id AND v.status = 'PUBLISHED'
      GROUP BY t.id, t.name
      ORDER BY t.id
      LIMIT #{size} OFFSET #{offset}
      """)
  List<TemplateRow> findPublishablePage(@Param("offset") long offset, @Param("size") int size);

  /** 与 {@link #findPublishablePage} 同条件，保证 `total` 与返回行一致。 */
  @Select(
      """
      SELECT COUNT(*) FROM (
        SELECT t.id FROM template t
        JOIN template_version v ON v.template_id = t.id AND v.status = 'PUBLISHED'
        GROUP BY t.id
      ) publishable
      """)
  long countPublishable();

  @Select("SELECT id, name FROM template WHERE id = #{id}")
  Optional<Template> findById(@Param("id") long id);

  @Update("UPDATE template SET name = #{name} WHERE id = #{id}")
  int updateName(@Param("id") long id, @Param("name") String name);

  /**
   * 锁住模板行（12 §E12）——**自动复制新草稿前必须先拿它**。
   *
   * <p>为什么必须有这一步：复制要先算出 `max(version_no) + 1`，而 `SELECT MAX(...)` 在 REPEATABLE READ 下是
   * **一致性读**——既不阻塞别人、也不阻止别人插入。两个请求于是可能各读到同一个 max、各插一份草稿，用户的
   * 两笔改动被分到两个版本里，谁都不报错。唯一键只能防"同一版本号插两次"，拦不住这种平行草稿。
   *
   * <p>锁在模板行上，粒度正好是"同一模板的复制串行化"；`FOR UPDATE` 是当前读，能看到别人刚提交的行，
   * 因此后面那次"复用已有草稿"的查询才是可信的。
   *
   * @return 存在则返回 id；模板不存在返回空（调用方转 2009）
   */
  @Select("SELECT id FROM template WHERE id = #{id} FOR UPDATE")
  Optional<Long> lockById(@Param("id") long id);
}
