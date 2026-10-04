package com.spe.context.system.internal.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `sys_user` 的写 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p><b>状态迁移一律是带前置条件的 UPDATE</b>（06 §2.4 / ADR-5）：`WHERE` 里同时钉住前置状态与
 * `version`，一条语句完成「判断 + 迁移」；影响行数为 0 由 Service 转 3xxx。本类不提供
 * 「查出实体改字段再 updateById」的全字段覆盖写法。
 *
 * <p>查询一律显式列名、不使用 `SELECT *`（06 §4）；列表分页走显式 `LIMIT/OFFSET` + `COUNT(*)`，
 * 因此不需要 MyBatis-Plus 的分页拦截器（10 §E13）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

  /** 分页取列表。`OFFSET` 由 Service 按「page 从 1 开始」换算（11 §11 / 09 D8）。 */
  @Select(
      """
      SELECT id, username, name, role, department, status, version
      FROM sys_user
      ORDER BY id
      LIMIT #{size} OFFSET #{offset}
      """)
  List<SysUser> findPage(@Param("offset") long offset, @Param("size") int size);

  @Select("SELECT COUNT(*) FROM sys_user")
  long countAll();

  @Select("SELECT id, username, name, role, department, status, version FROM sys_user WHERE id = #{id}")
  Optional<SysUser> findById(@Param("id") long id);

  /** 批量取（P3 校验审批链内人员在职，经 {@code UserQueryFacade}）。 */
  @Select(
      """
      <script>
      SELECT id, username, name, role, department, status, version
      FROM sys_user
      WHERE id IN
      <foreach item="item" collection="ids" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  List<SysUser> findByIds(@Param("ids") Collection<Long> ids);

  /** 登录用：唯一需要读出 `password_hash` 的查询，仅供 {@code AuthService} 内校验口令。 */
  @Select(
      """
      SELECT id, username, password_hash, name, role, department, status, version
      FROM sys_user
      WHERE username = #{username}
      """)
  Optional<SysUser> findByUsernameForAuth(@Param("username") String username);

  /**
   * 按部门 + 角色查在职用户 id（升序）——P3 发布任务的自动指派用（13 §E12）。
   *
   * <p>排序在 SQL 里而不是 Java 里：调用方要的是"部门内 id 最小者"这个**确定性**结果（13 §E12），
   * 让库来定序比让调用方自己排更不容易漂移。
   */
  @Select(
      """
      <script>
      SELECT id FROM sys_user
      WHERE status = 'ENABLED' AND department = #{department} AND role IN
      <foreach item="item" collection="roles" open="(" separator="," close=")">#{item}</foreach>
      ORDER BY id
      </script>
      """)
  List<Long> findEnabledIdsByDepartmentAndRoles(
      @Param("department") String department, @Param("roles") Collection<Role> roles);

  @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
  long countByUsername(@Param("username") String username);

  /**
   * 编辑档案（姓名/角色/部门）：`version` 是守卫条件，0 行表示档案已被他人改动 → 3xxx。
   *
   * <p>账号（`username`）不可改：它是登录标识，改它等于换账号，01 SM1 只要求「用户/角色/部门维护」。
   */
  @Update(
      """
      UPDATE sys_user
      SET name = #{name}, role = #{role}, department = #{department}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("name") String name,
      @Param("role") Role role,
      @Param("department") Department department,
      @Param("version") int version);

  /**
   * 启停账号（01 A4/SM1）：`status = #{expected}` 与 `version = #{version}` 双重前置条件。
   *
   * <p>账号状态是两态机（ENABLED/DISABLED），调用方传入目标状态、由 Service 用
   * {@code UserStatus.other()} 推出前置状态；「已经是目标状态」或「版本已变」都表现为 0 行 → 3xxx。
   */
  @Update(
      """
      UPDATE sys_user
      SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatusAndVersion(
      @Param("id") long id,
      @Param("target") UserStatus target,
      @Param("expected") UserStatus expected,
      @Param("version") int version);

  /** ADMIN 重置他人口令（01 SM1）：非状态迁移，故只以主键为条件；0 行 → 账号不存在。 */
  @Update("UPDATE sys_user SET password_hash = #{passwordHash}, version = version + 1 WHERE id = #{id}")
  int resetPassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

  /**
   * 本人改口令（01 A3 / E3）：以**原哈希**为前置条件，把「原口令校验」与「写入」合成一条原子语句——
   * 两个并发改口令请求只有一个能命中，另一个 0 行 → 3xxx。
   */
  @Update(
      """
      UPDATE sys_user
      SET password_hash = #{newPasswordHash}, version = version + 1
      WHERE id = #{id} AND password_hash = #{oldPasswordHash}
      """)
  int changePassword(
      @Param("id") long id,
      @Param("newPasswordHash") String newPasswordHash,
      @Param("oldPasswordHash") String oldPasswordHash);
}
