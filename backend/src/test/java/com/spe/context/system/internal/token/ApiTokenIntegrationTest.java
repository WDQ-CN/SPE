package com.spe.context.system.internal.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.spe.integration.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * M6 W1：Token 生成/列表/停用、哈希落库、独立过滤器与审计。
 *
 * <p>`/open/v1/**` 的控制器在 W2 才落地，本类用不存在的路径验证安全链：有效 Token 应穿过过滤器
 * 到 404（而不是 401/403），无效/停用 Token 401，JWT 403，Token 打 `/api/**` 401。
 */
@Transactional
class ApiTokenIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger(1);

  @Test
  @DisplayName("生成：明文只在响应一次、库内只存哈希、列表不回明文，且仅 ADMIN 可管理")
  void createStoresHashAndListsWithoutPlaintext() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = insertManagerAndLogin();
    String name = "M6-TOKEN-" + SEQ.getAndIncrement();

    MvcResult created =
        mockMvc
            .perform(authed(json(post("/api/api-tokens"), Map.of("name", name)), admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value(name))
            .andExpect(jsonPath("$.data.token").isString())
            .andReturn();
    JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    long id = data.path("id").asLong();
    String token = data.path("token").asText();
    String prefix = data.path("tokenPrefix").asText();
    assertThat(token).startsWith("spet_").hasSize(48);
    assertThat(prefix).startsWith("spet_").hasSize(13);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT token_hash, token_prefix, status, last_used_at FROM api_token WHERE id = ?", id);
    assertThat(row.get("token_hash").toString())
        .as("库内只能是 SHA-256 哈希")
        .hasSize(64)
        .doesNotContain(token)
        .isEqualTo(TokenHasher.hash(token));
    assertThat(row.get("token_prefix")).isEqualTo(prefix);
    assertThat(row.get("status")).isEqualTo("ENABLED");
    assertThat(row.get("last_used_at")).isNull();

    mockMvc
        .perform(authed(get("/api/api-tokens?page=1&size=10"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].id").value((int) id))
        .andExpect(jsonPath("$.data.list[0].version").value(0))
        .andExpect(jsonPath("$.data.list[0].token").doesNotExist())
        .andExpect(jsonPath("$.data.list[0].tokenHash").doesNotExist());

    Map<String, Object> audit =
        jdbcTemplate.queryForMap(
            "SELECT target_type, target_id, detail FROM audit_log"
                + " WHERE action = 'TOKEN_CREATE' AND target_id = ? ORDER BY id DESC LIMIT 1",
            id);
    assertThat(audit.get("target_type")).isEqualTo("TOKEN");
    assertThat(((Number) audit.get("target_id")).longValue()).isEqualTo(id);
    String detail = audit.get("detail").toString();
    assertThat(detail).contains(name).contains(prefix).doesNotContain(token);

    // 角色边界：MANAGER 不能生成/列表/停用
    mockMvc
        .perform(authed(json(post("/api/api-tokens"), Map.of("name", "M6-DENIED")), manager))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(authed(get("/api/api-tokens"), manager))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            authed(
                json(put("/api/api-tokens/" + id + "/disable"), Map.of("version", 0)),
                manager))
        .andExpect(status().isForbidden());

    // 缺名称 → 2001
    mockMvc
        .perform(authed(json(post("/api/api-tokens"), Map.of("name", "  ")), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("过滤器：有效 Token 穿过安全链；无效/停用 401；JWT 403；Token 打 /api 401")
  void tokenFilterSeparatesOpenApiFromUserApi() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Created created = createToken(admin, "M6-FILTER");

    mockMvc
        .perform(get("/open/v1/anything"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));
    mockMvc
        .perform(authed(get("/open/v1/anything"), admin))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(authed(get("/open/v1/anything"), created.token()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(authed(get("/api/users?page=1&size=1"), created.token()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));

    LocalDateTime touched =
        jdbcTemplate.queryForObject(
            "SELECT last_used_at FROM api_token WHERE id = ?", LocalDateTime.class, created.id());
    assertThat(touched).as("有效 Token 鉴权成功后应 touch last_used_at").isNotNull();

    // 停用后旧 Token 立即失效
    mockMvc
        .perform(
            authed(
                json(put("/api/api-tokens/" + created.id() + "/disable"), Map.of("version", 0)),
                admin))
        .andExpect(status().isOk());
    mockMvc
        .perform(authed(get("/open/v1/anything"), created.token()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("停用：版本不符 3001、二次停用 2036、不存在 2035；审计记录 TOKEN_DISABLE")
  void disableGuardsAndAudit() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Created created = createToken(admin, "M6-DISABLE");

    mockMvc
        .perform(
            authed(
                json(put("/api/api-tokens/" + created.id() + "/disable"), Map.of("version", 99)),
                admin))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    mockMvc
        .perform(
            authed(
                json(put("/api/api-tokens/" + created.id() + "/disable"), Map.of("version", 0)),
                admin))
        .andExpect(status().isOk());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM api_token WHERE id = ?", String.class, created.id()))
        .isEqualTo("DISABLED");
    Map<String, Object> audit =
        jdbcTemplate.queryForMap(
            "SELECT target_type, detail FROM audit_log"
                + " WHERE action = 'TOKEN_DISABLE' AND target_id = ? ORDER BY id DESC LIMIT 1",
            created.id());
    assertThat(audit.get("target_type")).isEqualTo("TOKEN");
    assertThat(audit.get("detail").toString()).doesNotContain(created.token());

    mockMvc
        .perform(
            authed(
                json(put("/api/api-tokens/" + created.id() + "/disable"), Map.of("version", 0)),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2036));

    mockMvc
        .perform(
            authed(
                json(put("/api/api-tokens/99999999/disable"), Map.of("version", 0)), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2035));
  }

  private Created createToken(String admin, String prefix) throws Exception {
    String name = prefix + "-" + SEQ.getAndIncrement();
    MvcResult result =
        mockMvc
            .perform(authed(json(post("/api/api-tokens"), Map.of("name", name)), admin))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    return new Created(data.path("id").asLong(), data.path("token").asText());
  }

  private String insertManagerAndLogin() throws Exception {
    String username = "m6-token-manager-" + SEQ.getAndIncrement();
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, 'MANAGER', 'PURCHASE', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "M6 Token Manager");
    return login(username, ADMIN_PASSWORD);
  }

  private record Created(long id, String token) {}
}
