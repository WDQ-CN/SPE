package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 盘点单 Mapper。 */
@Mapper
public interface InventoryCountMapper extends BaseMapper<InventoryCount> {

  String COLUMNS =
      "id, count_no, company_id, warehouse_id, status, snapshot_at, counted_by, reviewed_by,"
          + " reviewed_at, posted_by, posted_at, reason, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="warehouseId != null">AND warehouse_id = #{warehouseId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_count " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryCount> findPage(
      @Param("companyId") Long companyId,
      @Param("warehouseId") Long warehouseId,
      @Param("status") InventoryCountStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_count " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("warehouseId") Long warehouseId,
      @Param("status") InventoryCountStatus status);

  @Select("SELECT " + COLUMNS + " FROM inventory_count WHERE id = #{id}")
  Optional<InventoryCount> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM inventory_count WHERE id = #{id} FOR UPDATE")
  Optional<InventoryCount> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE inventory_count
      SET status = 'REVIEW', counted_by = #{operator}, version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','COUNTING')
      """)
  int markSubmitted(@Param("id") long id, @Param("operator") long operator);

  @Update(
      """
      UPDATE inventory_count
      SET status = 'APPROVED', reviewed_by = #{operator}, reviewed_at = NOW(3), version = version + 1
      WHERE id = #{id} AND status = 'REVIEW'
      """)
  int markApproved(@Param("id") long id, @Param("operator") long operator);

  @Update(
      """
      UPDATE inventory_count
      SET status = 'POSTED', posted_by = #{operator}, posted_at = NOW(3), version = version + 1
      WHERE id = #{id} AND status = 'APPROVED'
      """)
  int markPosted(@Param("id") long id, @Param("operator") long operator);

  @Update(
      """
      UPDATE inventory_count SET status = 'CANCELED', version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','COUNTING','REVIEW')
      """)
  int markCanceled(@Param("id") long id);
}
