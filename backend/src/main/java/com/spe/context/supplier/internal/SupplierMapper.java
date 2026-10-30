package com.spe.context.supplier.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.supplier.api.SupplierStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `supplier` 的写 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p><b>状态迁移一律是带前置条件的 UPDATE</b>（06 §2.4 / ADR-5）：`WHERE` 里同时钉住前置状态与
 * `version`，一条语句完成「判断 + 迁移」；影响行数为 0 由 Service 转 3xxx。不提供「查出实体改字段
 * 再 updateById」的全字段覆盖写法。
 *
 * <p><b>三条搜索实现约束</b>（10 §3 W3 的「mapper 实现约束」，均由「空条件」与「NULL」引发）：
 * <ol>
 *   <li>空条件**整段跳过**：`WHERE category LIKE '%%'` 会把 `category IS NULL` 的行过滤掉
 *       （实测 `NULL LIKE '%'` 为 NULL，在 WHERE 中即假）——分类为空的供应商会凭空消失；
 *   <li>`(code LIKE ? OR name LIKE ?)` **必须带括号**：与 `category LIKE ?` 用 `AND` 组合时
 *       `AND` 优先级高于 `OR`，漏括号会让优先级错位（语法不报错、只是结果错）；
 *   <li>`list` 与 `count` **共用同一条件片段**：分别拼两遍必然漂移，`total` 会与实际返回不一致。
 * </ol>
 *
 * <p>第 3 条用 Java 常量 + 注解拼接实现：MyBatis 的 `<sql>`/`<include>` 片段只能在 XML 映射文件里
 * 注册，写在注解里不会被收录（会报找不到 refid），而本项目不引入 XML 映射文件。
 */
@Mapper
public interface SupplierMapper extends BaseMapper<Supplier> {

  /** 列表与计数共用的搜索条件；`<where>` 会在两个条件都为空时不输出 WHERE，非空时自动去掉首个 AND。 */
  String SEARCH_CONDITION =
      """
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="category != null and category != ''">
          AND category LIKE CONCAT('%', #{category}, '%')
        </if>
      </where>
      """;

  /**
   * 分页取列表（**返回全部状态**：停用后仍要能在列表里看到并重新启用，10 §3 W3 的 R9-1）。
   * `OFFSET` 由 Service 按「page 从 1 开始」换算（09 D8）。`ORDER BY code` 保证分页稳定（code 唯一）。
   */
  @Select(
      "<script>SELECT id, code, name, category, status, version FROM supplier "
          + SEARCH_CONDITION
          + " ORDER BY code LIMIT #{size} OFFSET #{offset}</script>")
  List<Supplier> findPage(
      @Param("keyword") String keyword,
      @Param("category") String category,
      @Param("offset") long offset,
      @Param("size") int size);

  /** 与 {@link #findPage} 同一条件片段，保证 `total` 与返回行一致。 */
  @Select("<script>SELECT COUNT(*) FROM supplier " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("keyword") String keyword, @Param("category") String category);

  @Select("SELECT id, code, name, category, status, version FROM supplier WHERE id = #{id}")
  Optional<Supplier> findById(@Param("id") long id);

  /** 开放 API 用编码定位供应商（M6）；编码唯一键在库层兜底。 */
  @Select("SELECT id, code, name, category, status, version FROM supplier WHERE code = #{code}")
  Optional<Supplier> findByCode(@Param("code") String code);

  /** 批量取（P3 发布任务时校验/展示多家供应商，经 {@code SupplierQueryFacade}）。 */
  @Select(
      """
      <script>
      SELECT id, code, name, category, status, version FROM supplier
      WHERE id IN
      <foreach item="item" collection="ids" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  List<Supplier> findByIds(@Param("ids") Collection<Long> ids);

  /** 编码唯一的前置校验（唯一键 `uk_supplier_code` 是最后防线，06 §4）；大小写敏感性由 DB collation 决定。 */
  @Select("SELECT COUNT(*) FROM supplier WHERE code = #{code}")
  long countByCode(@Param("code") String code);

  /**
   * 编辑档案（编码/名称/分类）：`version` 是守卫条件，0 行表示档案已被他人改动 → 3xxx。
   *
   * <p>**不带状态条件**：停用后仍可编辑（E19）——停用只影响「能否被新任务选中」。
   */
  @Update(
      """
      UPDATE supplier
      SET code = #{code}, name = #{name}, category = #{category}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("category") String category,
      @Param("version") int version);

  /**
   * 启停（01 S2）：`status = #{expected}` 与 `version = #{version}` 双重前置条件。
   *
   * <p>状态是两态机（`ENABLED`/`DISABLED`），调用方传目标状态、由 Service 用
   * {@code SupplierStatus.other()} 推出前置状态；「已是目标状态」或「版本已变」都表现为 0 行 → 3xxx。
   */
  @Update(
      """
      UPDATE supplier
      SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatusAndVersion(
      @Param("id") long id,
      @Param("target") SupplierStatus target,
      @Param("expected") SupplierStatus expected,
      @Param("version") int version);
}
