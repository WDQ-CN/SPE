package com.spe.context.template.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `dimension` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p>改删都是带 `version` 前置条件的 UPDATE/DELETE（06 §2.4）：维度与指标也带乐观锁列，理由见 12 §E2——
 * 发布靠状态守卫，但**同一草稿上的并发编辑状态相同、守卫无效**，只能靠 `version`。
 */
@Mapper
public interface DimensionMapper extends BaseMapper<Dimension> {

  String COLUMNS =
      "id, template_version_id, name, weight, sort_no, suggested_dept, is_key, version,"
          + " src_dimension_id";

  /** 整棵树的第二层（12 §R-15 的"三条查询"之一）。 */
  @Select(
      "SELECT " + COLUMNS
          + " FROM dimension WHERE template_version_id = #{versionId} ORDER BY sort_no, id")
  List<Dimension> findByVersionId(@Param("versionId") long versionId);

  /**
   * 与 {@link #findByVersionId} 同一条查询，但用**共享锁**（当前读）。
   *
   * <p>为什么需要它：{@code ensureEditable} 复用"别人刚复制好的草稿"时，要按业务键把源版本的行与草稿的
   * 行配对。而那时本事务**可能已经建立过 read view**（调用方进入 service 前先做了一次 `requireDimension`
   * 这样的普通读），普通读走的是那个快照、**看不到对方刚提交的草稿内容** → 配对全空 → 调用方拿到
   * 2015「维度不存在」，而那一行明明在。
   *
   * <p>⚠️ 这是 CLAUDE 第 6 条（"加了锁不等于读到最新"）的第四次显形，而这次更隐蔽：**旧读发生在锁之后**。
   * 锁定读（`FOR UPDATE`/`FOR SHARE`）不建立也不使用 read view，所以它能看到最新已提交的数据。
   */
  @Select(
      "SELECT " + COLUMNS
          + " FROM dimension WHERE template_version_id = #{versionId}"
          + " ORDER BY sort_no, id FOR SHARE")
  List<Dimension> findByVersionIdForShare(@Param("versionId") long versionId);

  @Select("SELECT " + COLUMNS + " FROM dimension WHERE id = #{id}")
  Optional<Dimension> findById(@Param("id") long id);

  /**
   * 与 {@link #findById} 同一条查询，但用**排他锁**（当前读）——写路径的入口读用它。
   *
   * <p>**为什么要当前读**（CLAUDE「默认约定」第 6 条）：REPEATABLE READ 下 read view 由事务的第一条一致性
   * 读建立，入口先做一次普通读就把快照钉死了——之后即便 `ensureEditable` 拿到了锁，后续的普通读
   * （如 `IndicatorMapper.countByCode` 的编码查重）看到的仍是别人**提交前**的快照，于是"刚被释放的编码"
   * 被误判成仍被占用（**假 2014**，03 §31.7.5 的 P2）。入口读改成锁定读后，read view 由锁之后的
   * 第一条普通读建立，锁等到的那些提交都看得见。
   *
   * <p>⚠️⚠️ **必须是 `FOR UPDATE`，不能是 `FOR SHARE`**（04-076，我自己踩的）：写成 `FOR SHARE` 时，
   * 两个线程改**同一行**会退化成 ABBA 死锁 —— 双方先各持该行的 S 锁（S 与 S 兼容），接着都要去
   * `ensureEditable` 拿**版本行**的 X 锁（互斥，必有一个等），而先拿到版本行的那个回头要对本行做 UPDATE
   * （**S→X 升级**），被对方仍持着的 S 挡住 ⇒ 环成立。实测：`concurrentUpdatesOnSameRowOneWins`
   * 确定性变红（3/3），败者拿到 `DeadlockLoserDataAccessException` 而不是 3001。`FOR UPDATE` 让第二个
   * 线程**在第一条语句就排队**、手里不持任何锁 ⇒ 无环。
   */
  @Select("SELECT " + COLUMNS + " FROM dimension WHERE id = #{id} FOR UPDATE")
  Optional<Dimension> findByIdForUpdate(@Param("id") long id);

  /**
   * 发布校验的聚合视图：一条 SQL 取回每个维度的权重、指标数与指标权重合计。
   *
   * <p>`LEFT JOIN` 是为了**让没有指标的维度也出现在结果里**（`indicatorCount = 0`）——它正是"每维度至少 1 个
   * 指标"（01 T4）要拦的那种，用 `INNER JOIN` 会让违规的维度凭空消失、校验反而通过。
   */
  @Select(
      """
      SELECT d.id                       AS id,
             d.name                     AS name,
             d.weight                   AS dimensionWeight,
             COUNT(i.id)                AS indicatorCount,
             COALESCE(SUM(i.weight), 0) AS indicatorWeightSum
      FROM dimension d
      LEFT JOIN indicator i ON i.dimension_id = d.id
      WHERE d.template_version_id = #{versionId}
      GROUP BY d.id, d.name, d.weight
      ORDER BY d.sort_no, d.id
      """)
  List<DimensionValidationRow> findForValidation(@Param("versionId") long versionId);

  /**
   * 改维度：**两道前置条件**——`version`（乐观锁）+ `template_version_id`（**归属版本**）。
   *
   * <p>⚠️ 第二个条件是 04-074 加的**第二道闸**：`version` 只保证"这一行没被别人改过"，它**不能**保证
   * "这一行属于我想改的那个版本"。曾出现过一条静默写穿的路径（复用草稿时映射不完整 → 拿旧行 id 落到
   * **已发布版本**的行上，而那条行的 `version` 恰好还是调用方手里的值 ⇒ 守卫通过）。钉上生效版本号之后，
   * "写到非生效版本"在 SQL 层就**不可能成功**（0 行 → 3001），即使上层再犯同样的错也只是响亮失败。
   */
  @Update(
      """
      UPDATE dimension
      SET name = #{name}, weight = #{weight}, sort_no = #{sortNo},
          suggested_dept = #{suggestedDept}, is_key = #{isKey}, version = version + 1
      WHERE id = #{id} AND version = #{version} AND template_version_id = #{versionId}
      """)
  int update(
      @Param("id") long id,
      @Param("name") String name,
      @Param("weight") BigDecimal weight,
      @Param("sortNo") int sortNo,
      @Param("suggestedDept") String suggestedDept,
      @Param("isKey") boolean isKey,
      @Param("version") int version,
      @Param("versionId") long versionId);

  /**
   * 删除维度（12 §E13）：**两道前置条件**（`version` + 归属版本，理由见 {@link #update}），0 行 → 3001。
   *
   * <p>**其下的指标由 Service 在同一事务内一并删除**——表上没有外键（06 §4 不要求），不同步删就会留下
   * 孤儿指标：整树读取看不见它们，但自动复制会按 `template_version_id` 把它们一起复制过去，而发布校验
   * 只查"某维度无指标"，孤儿指标永远不会让校验失败。
   */
  @Update(
      "DELETE FROM dimension WHERE id = #{id} AND version = #{version}"
          + " AND template_version_id = #{versionId}")
  int delete(
      @Param("id") long id, @Param("version") int version, @Param("versionId") long versionId);
}
