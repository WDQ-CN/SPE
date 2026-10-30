package com.spe.context.alert.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `alert` 的写 Mapper + 列表查询（M5 W2/W3）。
 *
 * <p>列表不返回 SUPERSEDED（22 §2 D2）：它只在重算/解锁时被标记，面向用户的列表只留 UNREAD/HANDLED。
 * 处理是**守卫 UPDATE**：只有 UNREAD 能处理；0 行由 Service 复读区分 2032/2033（06 §2.4）。
 */
@Mapper
public interface AlertMapper extends BaseMapper<Alert> {

  String JOIN_COLUMNS =
      "a.id, a.task_supplier_id, a.supplier_id, s.code AS supplier_code, s.name AS supplier_name,"
          + " a.year, a.quarter, a.type, a.title, a.content, a.status, a.handled_by,"
          + " u.name AS handled_by_name, a.handled_comment, a.handled_at, a.created_at";

  String JOIN_FROM =
      " FROM alert a JOIN supplier s ON s.id = a.supplier_id"
          + " LEFT JOIN sys_user u ON u.id = a.handled_by";

  /**
   * 分页专用：先在派生表里用 `idx_alert_created` 倒序取出**一页的 id**，再回表 join。
   *
   * <p>写成常见的「WHERE + ORDER BY + LIMIT」单查询时，优化器即便有该索引也坚持全表扫 + 临时表 + filesort
   * （11 §9.4 的 20k 行探针：21.5ms vs 改写后 0.148ms，且改写不需要 FORCE INDEX）。
   * 外层 ORDER BY 不能省：join 之后行的物理序不再保证是 created_at DESC。
   */
  String PAGE_FROM =
      " FROM (SELECT id FROM alert WHERE status != 'SUPERSEDED'"
          + " <if test=\"status != null\"> AND status = #{status} </if>"
          + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}) p"
          + " JOIN alert a ON a.id = p.id"
          + " JOIN supplier s ON s.id = a.supplier_id"
          + " LEFT JOIN sys_user u ON u.id = a.handled_by";

  @Select("SELECT " + JOIN_COLUMNS + JOIN_FROM + " WHERE a.id = #{id}")
  Optional<AlertRow> findViewById(@Param("id") long id);

  @Select(
      "<script>"
          + "SELECT "
          + JOIN_COLUMNS
          + PAGE_FROM
          + " ORDER BY a.created_at DESC, a.id DESC"
          + "</script>")
  List<AlertRow> findPage(
      @Param("status") String status, @Param("offset") long offset, @Param("size") int size);

  @Select(
      "<script>"
          + "SELECT COUNT(*) FROM alert a WHERE a.status != 'SUPERSEDED'"
          + " <if test=\"status != null\"> AND a.status = #{status} </if>"
          + "</script>")
  long countPage(@Param("status") String status);

  /** 该供应商该周期的活动预警（UNREAD/HANDLED）：重算前先取出来用于 superseded_by 关联。 */
  @Select(
      "SELECT id, supplier_id, year, quarter, type, status FROM alert"
          + " WHERE supplier_id = #{supplierId} AND year = #{year} AND quarter = #{quarter}"
          + " AND status IN ('UNREAD','HANDLED') ORDER BY id")
  List<Alert> findActive(
      @Param("supplierId") long supplierId,
      @Param("year") int year,
      @Param("quarter") int quarter);

  /** 把该供应商该周期的活动预警置 SUPERSEDED（定稿重算与解锁共用；22 §2 D2）。 */
  @Update(
      "UPDATE alert SET status = 'SUPERSEDED'"
          + " WHERE supplier_id = #{supplierId} AND year = #{year} AND quarter = #{quarter}"
          + " AND status IN ('UNREAD','HANDLED')")
  int supersedeActive(
      @Param("supplierId") long supplierId,
      @Param("year") int year,
      @Param("quarter") int quarter);

  /** 旧预警指向替换它的新记录（仅在"新预警里同类型恰好一条"时调用，见 AlertListener）。 */
  @Update("UPDATE alert SET superseded_by = #{supersededBy} WHERE id = #{id}")
  int linkSupersededBy(@Param("id") long id, @Param("supersededBy") long supersededBy);

  /** 处理（守卫）：只有 UNREAD 能处理；0 行交由 Service 复读定夺。 */
  @Update(
      "UPDATE alert SET status = 'HANDLED', handled_by = #{handledBy},"
          + " handled_comment = #{comment}, handled_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND status = 'UNREAD'")
  int handle(
      @Param("id") long id, @Param("handledBy") long handledBy, @Param("comment") String comment);
}
