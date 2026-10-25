package com.spe.context.evaluation.internal.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

/**
 * W1.6 的最小集成用例（17 §3 / §4 判据 D1-2）：V13 的两张表**可写、可读、兜底键会拦**。
 *
 * <p>三条盯守，每条对应 17 §5 的一个变异点：
 * <ul>
 *   <li>插入后读回的 {@code round == 1} 且能读回 —— 盯**显式写入**（变异：COLUMNS 去掉 round
 *       ⇒ 实体 round=null ⇒ 变红）与 {@code uk_approval_record}（变异 1：去掉唯一键 ⇒ 第 3 步变红）；</li>
 *   <li>同 (ts, round, seq) 第二条必须被数据库拒绝 —— 变异 1 的直接形态；</li>
 *   <li>V13 的四个键/索引在库里真实存在 —— 盯变异 3（去掉 {@code idx_ts_status_node} ⇒ 红一张）。</li>
 * </ul>
 *
 * <p>断言外部协议（information_schema）时打印实际值 —— 04-062 的教训：失败信息里要有"实际是什么"。
 */
class V13ApprovalTablesIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ApprovalRecordMapper approvalRecordMapper;
  @Autowired private TaskSupplierDimensionResultMapper resultMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("W1.6 V13 两表可写可读：round 显式落 1、唯一键拦重复节点、快照按维度排齐")
  void tablesRoundTripAndGuards_Bite() {
    // ① approval_record：显式写 round=1（不依赖列 DEFAULT —— 17 §3 W1.7）
    ApprovalRecord record = new ApprovalRecord();
    record.setTaskSupplierId(90001L);
    record.setRound(1);
    record.setSeq(1);
    record.setApproverId(100L);
    record.setAction("APPROVE");
    record.setComment("通过");
    assertThat(approvalRecordMapper.insertBatch(List.of(record)))
        .as("approval_record 批量插入应写入 1 行")
        .isEqualTo(1);

    List<ApprovalRecord> loaded = approvalRecordMapper.findByTaskSupplier(90001L);
    assertThat(loaded).as("按评估单读回应有 1 行，实际=%s", loaded).hasSize(1);
    assertThat(loaded.get(0).getRound())
        .as("round 必须显式落库为 1（读不到=1 说明 COLUMNS 或插入语句漏了 round）")
        .isEqualTo(1);
    assertThat(loaded.get(0).getAction()).isEqualTo("APPROVE");
    // 存疑 13（03 §38）：时间线要把"什么时候审的"读回来。COLUMNS 漏了 created_at/updated_at 时，
    // 上面四条断言全绿、实体里的时间是 null —— 只有这一条能抓到（变异：从 COLUMNS 删掉 created_at ⇒ 红）
    assertThat(loaded.get(0).getCreatedAt())
        .as("created_at 必须能读回（W5 的时间线要用；COLUMNS 漏列时它是 null）")
        .isNotNull();
    assertThat(loaded.get(0).getUpdatedAt())
        .as("updated_at 必须能读回（只增型表里它恒等于 created_at，03 §37 P2-2）")
        .isNotNull();

    // ② 同 (ts, round, seq) 第二条被唯一键拦（uk_approval_record 的数据库兜底）
    ApprovalRecord duplicate = new ApprovalRecord();
    duplicate.setTaskSupplierId(90001L);
    duplicate.setRound(1);
    duplicate.setSeq(1);
    duplicate.setApproverId(101L);
    duplicate.setAction("REJECT");
    duplicate.setComment("只能有一条终局记录");
    assertThatThrownBy(() -> approvalRecordMapper.insertBatch(List.of(duplicate)))
        .as("同 (task_supplier, round, seq) 的第二条必须撞 uk_approval_record")
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(approvalRecordMapper.findByTaskSupplier(90001L))
        .as("被拒的重复行不得落库；留在表里的应是第①步那 1 行")
        .hasSize(1);

    // ③ 维度结果快照：不同维度各一行；本次只写 2 行（模拟两维模板，行数不变量的盯守交给 W4 的服务层断言 15 §E13）
    TaskSupplierDimensionResult quality = new TaskSupplierDimensionResult();
    quality.setTaskSupplierId(90001L);
    quality.setDimensionId(11L);
    quality.setDimensionName("质量");
    quality.setWeight(new BigDecimal("40.00"));
    quality.setScore(new BigDecimal("86.50"));
    quality.setGrade("A");
    TaskSupplierDimensionResult delivery = new TaskSupplierDimensionResult();
    delivery.setTaskSupplierId(90001L);
    delivery.setDimensionId(22L);
    delivery.setDimensionName("交付物流");
    delivery.setWeight(new BigDecimal("30.00"));
    delivery.setScore(new BigDecimal("78.20"));
    delivery.setGrade("B");
    assertThat(resultMapper.insertBatch(List.of(quality, delivery)))
        .as("快照批量插入应写入 2 行")
        .isEqualTo(2);
    List<TaskSupplierDimensionResult> snapshot = resultMapper.findByTaskSupplier(90001L);
    assertThat(snapshot)
        .as("按评估单读回只有本次插入的 2 行（读不到=2 说明 COLUMNS 漏列），实际=%s", snapshot)
        .hasSize(2);
    assertThat(snapshot).allSatisfy(r -> {
      assertThat(r.getDimensionName()).isNotEmpty();
      assertThat(r.getWeight()).isNotNull();
      assertThat(r.getCreatedAt()).as("快照也要能读回定稿时间（报告页）").isNotNull();
    });
  }

  @Test
  @DisplayName("W1.6 V13 的四个键/索引在库里真实存在（盯守变异 3：idx_ts_status_node 被去掉用例变红）")
  void v13KeysAndIndexExist() {
    // ⚠️ information_schema.statistics 是【每个索引 × 每列】一行（四列索引返回 4 行）⇒ 必须 DISTINCT，
    // 否则 containsExactlyInAnyOrder 被按行展开的集合炸掉（本用例第一次运行就栽在这，03 §37.6）
    List<String> actual =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE()"
                + " AND ((table_name = 'approval_record' AND index_name IN ('uk_approval_record','idx_approval_record_tl'))"
                + "   OR (table_name = 'task_supplier_dimension_result' AND index_name IN ('uk_ts_dimension'))"
                + "   OR (table_name = 'task_supplier' AND index_name IN ('idx_ts_status_node')))",
            String.class);
    assertThat(actual)
        .as("V13 应落的四个键/索引，实际库里的是：%s", actual)
        .containsExactlyInAnyOrder("uk_approval_record", "idx_approval_record_tl", "uk_ts_dimension", "idx_ts_status_node");
  }
}
