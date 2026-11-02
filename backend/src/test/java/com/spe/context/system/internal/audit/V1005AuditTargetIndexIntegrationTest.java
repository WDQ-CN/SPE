package com.spe.context.system.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.integration.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** V1005：详情审计摘要的目标索引必须由数据库真实建立（迁移在干净库一次应用）。 */
class V1005AuditTargetIndexIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("V1005：audit_log 的 idx_audit_target 列顺序与查询同序")
  void auditTargetIndexExists() {
    List<String> columns =
        jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'audit_log'"
                + " AND index_name = 'idx_audit_target' ORDER BY seq_in_index",
            String.class);

    assertThat(columns).containsExactly("target_type", "target_id", "created_at", "id");
  }
}
