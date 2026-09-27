package com.spe.context.template.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `template_version` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p><b>状态迁移一律是带前置条件的 UPDATE</b>（06 §2.4 / ADR-5）：{@link #publish} 的 `WHERE` 里同时钉住
 * 前置状态与 `version`，一条语句完成「判断 + 迁移」，0 行由 Service 转 3001。不提供"查出实体改字段再
 * updateById"的全字段覆盖写法。
 */
@Mapper
public interface TemplateVersionMapper extends BaseMapper<TemplateVersion> {

  /** 显式列名，无时间列以外的额外投影（`created_at`/`updated_at` 本阶段不读）。 */
  String COLUMNS =
      "id, template_id, version_no, status, copied_from_version_id, published_at, version";

  @Select("SELECT " + COLUMNS + " FROM template_version WHERE id = #{id}")
  Optional<TemplateVersion> findById(@Param("id") long id);

  /**
   * **当前读**并锁住版本行（12 §E12 / 04-057）。
   *
   * <p>用于"按状态决定后续动作"的地方——普通读在 REPEATABLE READ 下走事务开始时的快照，读到的是**过期的
   * 状态**：另一个事务可能在这两步之间把草稿发布了。而 `FOR UPDATE` 是当前读，同时与「发布」那条
   * `UPDATE template_version ...` 天然互斥，因此拿到的状态在事务内不会再变。
   *
   * <p>这不是"更保险一点"，而是正确性：若拿快照里的 DRAFT 去插入维度，就会往一个**已发布版本**里加内容，
   * 而版本守卫只能拦住 UPDATE、拦不住 INSERT（05 ADR-3 的「发布即冻结」就此失守）。
   */
  @Select("SELECT " + COLUMNS + " FROM template_version WHERE id = #{id} FOR UPDATE")
  Optional<TemplateVersion> findByIdForUpdate(@Param("id") long id);

  /** 最新版本（不分状态），供"进入编辑器"定位初始版本。 */
  @Select(
      "SELECT " + COLUMNS
          + " FROM template_version WHERE template_id = #{templateId}"
          + " ORDER BY version_no DESC LIMIT 1")
  Optional<TemplateVersion> findLatestByTemplateId(@Param("templateId") long templateId);

  /** 已发布版本，版本号降序（最新的在前）——供 P3 选版本（01 J1）。 */
  @Select(
      "SELECT " + COLUMNS
          + " FROM template_version WHERE template_id = #{templateId} AND status = 'PUBLISHED'"
          + " ORDER BY version_no DESC")
  List<TemplateVersion> findPublishedByTemplateId(@Param("templateId") long templateId);

  /**
   * 批量取多个模板的已发布版本（供 P3 的选择器一次拿到各模板的最新版本，**避免逐模板回查**）。
   *
   * <p>结果按 `template_id, version_no DESC` 排好序，调用方取每个模板的第一条即"最新已发布版本"。
   */
  @Select(
      "<script>SELECT "
          + COLUMNS
          + " FROM template_version WHERE status = 'PUBLISHED' AND template_id IN"
          + " <foreach item=\"item\" collection=\"templateIds\" open=\"(\" separator=\",\" close=\")\">"
          + "#{item}</foreach> ORDER BY template_id, version_no DESC</script>")
  List<TemplateVersion> findPublishedByTemplateIds(@Param("templateIds") Collection<Long> templateIds);

  /**
   * 复用查询（12 §E5）：同模板、**源自同一个已发布版本**、且仍是草稿的那个版本。
   *
   * <p>⚠️ **末尾的 `FOR UPDATE` 不是可选项**。调用方已持有版本行锁，但 MySQL 默认 REPEATABLE READ 下
   * **普通读走的是事务开始时的快照**——第二个事务即便等到了"同一模板下复制"的那把锁
   * （{@link #findMaxVersionNoForUpdate}），也看不到第一个刚提交的草稿，于是判定"没有同源草稿"、
   * 自己插一份，撞唯一键变成 3001 并留下孤儿草稿。这是并发用例实测出来的（不写 `FOR UPDATE` 时它确实红），
   * 不是理论推演。
   */
  @Select(
      "SELECT " + COLUMNS
          + " FROM template_version WHERE template_id = #{templateId}"
          + " AND copied_from_version_id = #{sourceVersionId} AND status = 'DRAFT'"
          + " ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
  Optional<TemplateVersion> findDraftCopiedFrom(
      @Param("templateId") long templateId, @Param("sourceVersionId") long sourceVersionId);

  /**
   * 当前最大的版本号，**当前读并锁住那一行**。
   *
   * <p>⚠️ **它是"同一模板下的复制"唯一的串行化点**（04-080）：模板行锁已删除——那把是**冗余**的，
   * 两把锁会互相掩盖对方的失效（实测：只动其中任一把，跨版本并发复制用例都不红）。另一个并发复制方要读的
   * 正是这一行（或该模板尚无版本时的那段间隙），因此它会阻塞到本事务提交；而 `FOR UPDATE` 是当前读、
   * 看得见等到的那些提交 ⇒ 随后算出的 `max + 1` 才是权威的。
   *
   * <p>为什么不用 `SELECT MAX(version_no)`：① 聚合函数上加不了 `FOR UPDATE`，而普通读在 REPEATABLE READ
   * 下会读到过期快照，算出已经被别人用掉的版本号（实测：把它降级成快照，跨版本并发复制用例会红）；
   * ② 取"最大那一行"本身就能顺带把它锁住，语义也更直白。模板下还没有任何版本时返回空，调用方按 0 处理。
   */
  @Select(
      "SELECT version_no FROM template_version WHERE template_id = #{templateId}"
          + " ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
  Optional<Integer> findMaxVersionNoForUpdate(@Param("templateId") long templateId);

  /**
   * 发布（01 T1 / 12 §E12）：`status` 与 `version` 双重前置条件，0 行 → 3001。
   *
   * <p>发布前的五类校验在同一事务内先跑，但**判定落在这条 UPDATE 上**：校验通过而状态已被别人改走时，
   * `status = 'DRAFT'` 不成立，这次发布照样失败。
   *
   * <p>时间由**库**生成（`CURRENT_TIMESTAMP(3)`，与列默认值同源），不从 Java 传：连接时区已固定 UTC，
   * 让库自己写就不会在两个时区口径之间来回换算。
   */
  @Update(
      """
      UPDATE template_version
      SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3), version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}
      """)
  int publish(@Param("id") long id, @Param("version") int version);
}
