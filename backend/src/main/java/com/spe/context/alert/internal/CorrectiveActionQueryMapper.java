package com.spe.context.alert.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 整改任务的**只读 QueryMapper**（W3；05 §8.3 的读放开）。
 *
 * <p>只提供 `@Select`，写路径仍在 {@link CorrectiveActionMapper} 并由 Service 带 version/状态守卫。
 * 列表先取一页 id 再回表 join（与 {@link AlertMapper#PAGE_FROM} 同形），避免分页时把整张表先连出来。
 */
@Mapper
public interface CorrectiveActionQueryMapper {

  String COLUMNS =
      "ca.id, ca.alert_id, a.type AS alert_type, a.title AS alert_title,"
          + " ca.task_supplier_id, ca.supplier_id, s.code AS supplier_code, s.name AS supplier_name,"
          + " ca.dimension_id, d.name AS dimension_name,"
          + " ca.baseline_year, ca.baseline_quarter, ca.baseline_score, ca.target_score,"
          + " ca.owner_id, u.name AS owner_name, ca.due_date, ca.measure, ca.expected_result,"
          + " ca.status, ca.submit_comment, ca.submitted_at,"
          + " ca.verify_year, ca.verify_quarter, ca.verify_task_supplier_id, ca.verify_score,"
          + " ca.verify_outcome, ca.verified_by, vu.name AS verified_by_name,"
          + " ca.verify_comment, ca.verified_at, ca.canceled_reason,"
          + " ca.created_by, cu.name AS created_by_name, ca.version, ca.created_at, ca.updated_at";

  String FROM =
      " FROM corrective_action ca"
          + " JOIN alert a ON a.id = ca.alert_id"
          + " JOIN supplier s ON s.id = ca.supplier_id"
          + " LEFT JOIN dimension d ON d.id = ca.dimension_id"
          + " LEFT JOIN sys_user u ON u.id = ca.owner_id"
          + " LEFT JOIN sys_user vu ON vu.id = ca.verified_by"
          + " LEFT JOIN sys_user cu ON cu.id = ca.created_by";

  String PAGE_JOINS =
      " JOIN corrective_action ca ON ca.id = p.id"
          + " JOIN alert a ON a.id = ca.alert_id"
          + " JOIN supplier s ON s.id = ca.supplier_id"
          + " LEFT JOIN dimension d ON d.id = ca.dimension_id"
          + " LEFT JOIN sys_user u ON u.id = ca.owner_id"
          + " LEFT JOIN sys_user vu ON vu.id = ca.verified_by"
          + " LEFT JOIN sys_user cu ON cu.id = ca.created_by";

  String FILTERS =
      "<if test='status != null'> AND status = #{status} </if>"
          + "<if test='supplierId != null'> AND supplier_id = #{supplierId} </if>"
          + "<if test='ownerId != null'> AND owner_id = #{ownerId} </if>"
          + "<if test='overdue != null and overdue'>"
          + " AND due_date &lt; #{today} AND status NOT IN ('VERIFIED','CANCELED')"
          + "</if>"
          + "<if test='overdue != null and !overdue'>"
          + " AND (due_date &gt;= #{today} OR status IN ('VERIFIED','CANCELED'))"
          + "</if>";

  @Select("SELECT " + COLUMNS + FROM + " WHERE ca.id = #{id}")
  Optional<CorrectiveActionRow> findDetailById(@Param("id") long id);

  @Select(
      "<script>"
          + "SELECT "
          + COLUMNS
          + " FROM (SELECT id FROM corrective_action WHERE 1 = 1 "
          + FILTERS
          + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}) p"
          + PAGE_JOINS
          + " ORDER BY ca.created_at DESC, ca.id DESC"
          + "</script>")
  List<CorrectiveActionRow> findPage(
      @Param("status") String status,
      @Param("supplierId") Long supplierId,
      @Param("ownerId") Long ownerId,
      @Param("overdue") Boolean overdue,
      @Param("today") LocalDate today,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      "<script>"
          + "SELECT COUNT(*) FROM corrective_action WHERE 1 = 1 "
          + FILTERS
          + "</script>")
  long countPage(
      @Param("status") String status,
      @Param("supplierId") Long supplierId,
      @Param("ownerId") Long ownerId,
      @Param("overdue") Boolean overdue,
      @Param("today") LocalDate today);
}
