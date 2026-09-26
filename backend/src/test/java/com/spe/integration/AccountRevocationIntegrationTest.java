package com.spe.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.internal.security.JwtPayload;
import com.spe.context.system.internal.security.JwtService;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号状态/角色的**即时生效**语义（03 §16 的 SEC-1）：JWT 只证明签发时刻的身份，停用与降权不得等到 TTL 到期。
 *
 * <p>实测依据：修复前用「先登录 → 管理员停用 → 再用同一令牌」探测，第二次仍返回 `200 code=0`；
 * 修复后同一探测返回 `401/1001`。停用与降权都由令牌校验那一层兜住，而不是靠前端或登录口。
 */
@Transactional
class AccountRevocationIntegrationTest extends AbstractIntegrationTest {

  /** 用于签出「签名合法但 uid 不存在」的令牌：证明自签令牌不再能凭签名通过。 */
  @Autowired private JwtService jwtService;

  private String createUser(String adminToken, String username, String role) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "账号状态用例",
                        "role", role,
                        "department", "PURCHASE")),
                adminToken))
        .andExpect(status().isOk());
    return login(username, DEFAULT_PASSWORD);
  }

  @Test
  @DisplayName("停用账号后：已签发的令牌立即失效（401），该账号也不能再登录（1002）")
  void disabledAccountCannotUseIssuedToken() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String token = createUser(adminToken, "revoked_user", "MANAGER");
    long uid = userIdOf("revoked_user");

    // 停用前令牌可用（MANAGER 对供应商端点有权限）
    mockMvc.perform(authed(get("/api/suppliers"), token)).andExpect(status().isOk());

    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + uid + "/status"), Map.of("status", "DISABLED", "version", 0)),
                adminToken))
        .andExpect(status().isOk());

    // 停用后：同一令牌立刻 401（未认证），而不是继续放行到 TTL 到期
    mockMvc
        .perform(authed(get("/api/suppliers"), token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));

    // 登录口同样拦住
    mockMvc
        .perform(
            json(post("/api/auth/login"), Map.of("username", "revoked_user", "password", DEFAULT_PASSWORD)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1002));
  }

  @Test
  @DisplayName("降权后：旧令牌按库中当前角色鉴权（ADMIN→VIEWER 后访问用户管理变 403）")
  void roleChangeTakesEffectImmediately() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String token = createUser(adminToken, "demoted_user", "ADMIN");
    long uid = userIdOf("demoted_user");

    mockMvc.perform(authed(get("/api/users"), token)).andExpect(status().isOk());

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + uid),
                    Map.of(
                        "name", "账号状态用例",
                        "role", "VIEWER",
                        "department", "PURCHASE",
                        "version", 0)),
                adminToken))
        .andExpect(status().isOk());

    // 已认证但无权限：403 而不是 200——令牌里的 ADMIN 声明不再算数
    mockMvc
        .perform(authed(get("/api/users"), token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
  }

  @Test
  @DisplayName("签名合法但 uid 在库里不存在：401 而不是 403（自签令牌不再能凭签名通过）")
  void signedTokenForUnknownUserIsUnauthorized() throws Exception {
    String ghost =
        jwtService.issue(
            new JwtPayload(999_999L, "ghost", Set.of("ADMIN"), "PURCHASE"), Instant.now());

    mockMvc
        .perform(authed(get("/api/suppliers"), ghost))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));
  }
}
