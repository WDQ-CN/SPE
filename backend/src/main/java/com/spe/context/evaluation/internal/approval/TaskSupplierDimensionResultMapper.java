package com.spe.context.evaluation.internal.approval;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code task_supplier_dimension_result} 的 Mapper（V13）。
 *
 * <p>T3 定稿（15 §E6）随 W4 落地；这里先备好**整批写与读取** —— 快照型表（02 §3.3），
 * uk_ts_dimension(task_supplier_id, dimension_id) 保证每维一行。
 *
 * <p>⚠️ 与 {@link ApprovalRecordMapper} 同理**不继承 `BaseMapper`**：快照一经写入就不再更新，
 * 继承它等于把"只增不改"留给自觉（03 §38 的 P2-3，由 ArchUnit R-I 钉住）。
 */
@Mapper
public interface TaskSupplierDimensionResultMapper {

  /** 含时间列，理由同 {@link ApprovalRecordMapper#COLUMNS}（03 §38 存疑 13）。 */
  String COLUMNS =
      "id, task_supplier_id, dimension_id, dimension_name, weight, score, grade, created_at, updated_at";

  /** 定稿时整批写（同一 T3 事务）；行数必须等于维度数（15 §E13 的断言由服务层做）。 */
  @Insert(
      """
      <script>
      INSERT INTO task_supplier_dimension_result (task_supplier_id, dimension_id, dimension_name, weight, score, grade) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.taskSupplierId}, #{item.dimensionId}, #{item.dimensionName}, #{item.weight}, #{item.score}, #{item.grade})
      </foreach>
      </script>
      """)
  int insertBatch(@Param("items") List<TaskSupplierDimensionResult> items);

  @Select(
      "SELECT " + COLUMNS + " FROM task_supplier_dimension_result"
          + " WHERE task_supplier_id = #{taskSupplierId} ORDER BY dimension_id")
  List<TaskSupplierDimensionResult> findByTaskSupplier(@Param("taskSupplierId") long taskSupplierId);

  /** 结果列表批读：一次取回多个评估单的维度快照，避免逐供应商回查。 */
  @Select(
      """
      <script>
      SELECT id, task_supplier_id, dimension_id, dimension_name, weight, score, grade, created_at, updated_at
      FROM task_supplier_dimension_result
      WHERE task_supplier_id IN
      <foreach item="item" collection="taskSupplierIds" open="(" separator="," close=")">#{item}</foreach>
      ORDER BY task_supplier_id, dimension_id
      </script>
      """)
  List<TaskSupplierDimensionResult> findByTaskSupplierIds(
      @Param("taskSupplierIds") java.util.Collection<Long> taskSupplierIds);
}
