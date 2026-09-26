package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.AuditFacade;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * T6 的直接证据（10 §3 W3 的验收 C4 / 03 §14 的 R9-12）：**审计写入失败 → 业务行回滚**。
 *
 * <p>W2 已把这条语义实现进 `AuditFacadeImpl`（序列化失败即抛出），但一直没有用例证明它——
 * 本类补上。刻意**不加** `@Transactional`：要断言的是「回滚之后业务表没有那一行」，
 * 测试自己若也在事务里，回滚语义会被外层掩盖（04-027 的教训）。
 *
 * <p>`@MockitoBean` 替换 `AuditFacade` 会让 Spring 建立另一个上下文缓存条目；共享容器由
 * `TestcontainersConfiguration` 的静态单例保证只起一次。
 */
class AuditRollbackIntegrationTest extends AbstractIntegrationTest {

  @MockitoBean private AuditFacade auditFacade;

  @Test
  @DisplayName("审计抛异常 → 供应商未被创建（业务与审计同生共死）")
  void auditFailureRollsBackBusinessRow() throws Exception {
    doThrow(new IllegalStateException("审计不可用")).when(auditFacade).record(any(), any(), any(), any());
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String code = "W3T-ROLLBACK";

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/suppliers"),
                    Map.of("code", code, "name", "回滚用例", "category", "电子")),
                adminToken))
        .andExpect(status().is5xxServerError());

    Long rows =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM supplier WHERE code = ?", Long.class, code);
    assertThat(rows).as("审计失败时业务行必须回滚（05 §5.5 T6）").isZero();
  }
}
