package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 上传超限与缺部件必须走**真 Tomcat**（03 §38 的 P1-6/P1-7 欠的那两条用例）。
 *
 * <p><b>为什么单独开一个 RANDOM_PORT 的上下文</b>：这两条路径都在 MockMvc 里做不出来——
 * {@code MockHttpServletRequest} 不经过 {@code StandardServletMultipartResolver} 的上限判定，
 * 所以 {@code MaxUploadSizeExceededException} 在 mock 环境下**永远不会发生**；写一条"看起来测了 413"
 * 的 mock 用例就是恒真判据（06 §8 第 33 条点名的病）。代价是多一个 Spring 上下文与几秒启动，
 * 换来的是这两条判据真的能红。
 *
 * <p>上限被本类的 {@code @TestPropertySource} 压到 1KB：被测的是"**超限时返回什么形状**"，
 * 不是"20MB 能不能传"——用 128KB 的载荷就能拿到同一条 413 + {@code code 2030}。
 * 生产值（20MB/22MB）与 nginx 的一致性另有门禁 {@code upload-limits} 守着（B4 已落地）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
    properties = {
      "app.jwt.secret=spe-integration-test-secret-0123456789abcdef",
      "spring.servlet.multipart.max-file-size=1KB",
      "spring.servlet.multipart.max-request-size=2KB",
      // ⚠️ 不设这一条，用例拿不到 413：Tomcat 默认 max-swallow-size=2MB，请求体超限时它会"吞掉"剩余字节，
      // 吞不完就直接断连 ⇒ 客户端看到的是连接被重置而不是响应。生产里 20MB 上限下溢出量远小于 2MB，
      // 不需要这个设置（这一点连同"经 nginx 的 >20m 是裸 413"一起记在 11 §9.1）。
      "server.tomcat.max-swallow-size=-1",
    })
class UploadLimitApiIntegrationTest {

  private static final String ADMIN_USERNAME = "admin";
  private static final String ADMIN_PASSWORD = "Admin@12345";
  private static final String DEFAULT_PASSWORD = "Passw0rd!";

  @Autowired private TestRestTemplate rest;

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("超过 max-file-size 的上传 ⇒ 413 + code 2030，而不是没有 code 的 ProblemDetail")
  void oversizedUploadAnswerWithCode2030() throws Exception {
    String manager = loginAsManager("upload-cap-mgr");

    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("file", namedResource(new byte[128 * 1024], "big.xlsx"));

    ResponseEntity<String> response =
        rest.exchange(
            "/api/tasks/1/import",
            HttpMethod.POST,
            new HttpEntity<>(parts, authedHeaders(manager, MediaType.MULTIPART_FORM_DATA)),
            String.class);

    assertThat(response.getStatusCode())
        .as("响应体：%s", response.getBody())
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    JsonNode body = json(response);
    assertThat(body.path("code").asInt())
        .as("2030 = IMPORT_FILE_TOO_LARGE（响应体：%s）", response.getBody())
        .isEqualTo(2030);
    assertThat(body.path("message").asText()).isNotBlank();
  }

  /**
   * 缺 `file` 部件走的是哪条 handler 由 Spring 的解析顺序决定，所以判据钉的是**这一条 handler 的文案**
   * （"请求缺少必需的文件部分"）：如果哪天它变成 `MissingServletRequestParameterException` 的文案，
   * 这条用例会红并提醒我们"两条都得继续返回带 code 的 Result"——那才是不变量。
   */
  @Test
  @DisplayName("multipart 请求没带文件部件 ⇒ 400 + code 2001 + 缺部件的文案")
  void uploadWithoutPartAnswersWithResult() throws Exception {
    String manager = loginAsManager("upload-part-mgr");

    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("note", "这一发故意不带 file");

    ResponseEntity<String> response =
        rest.exchange(
            "/api/tasks/1/import",
            HttpMethod.POST,
            new HttpEntity<>(parts, authedHeaders(manager, MediaType.MULTIPART_FORM_DATA)),
            String.class);

    assertThat(response.getStatusCode())
        .as("响应体：%s", response.getBody())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode body = json(response);
    assertThat(body.path("code").asInt()).isEqualTo(2001);
    assertThat(body.path("message").asText()).isEqualTo("请求缺少必需的文件部分");
  }

  // ---------------------------------------------------------------- 工具

  /** 导入端点的角色是 MANAGER/EVALUATOR（TaskImportController 的类级注解），ADMIN 会被 4001 抢先。 */
  private String loginAsManager(String username) throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    ResponseEntity<String> created =
        rest.exchange(
            "/api/users",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "username",
                    username,
                    "password", DEFAULT_PASSWORD,
                    "name", "上传上限用例经理",
                    "role",
                    "MANAGER",
                    "department",
                    "PURCHASE"),
                authedHeaders(admin, MediaType.APPLICATION_JSON)),
            String.class);
    assertThat(created.getStatusCode())
        .as("建一个 MANAGER 探针账号（响应体：%s）", created.getBody())
        .isEqualTo(HttpStatus.OK);
    return login(username, DEFAULT_PASSWORD);
  }

  private String login(String username, String password) throws Exception {
    ResponseEntity<String> response =
        rest.exchange(
            "/api/auth/login",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("username", username, "password", password), jsonHeaders()),
            String.class);
    String token = json(response).path("data").path("token").asText();
    assertThat(token).as("登录拿不到令牌：status=%s body=%s", response.getStatusCode(), response.getBody())
        .isNotBlank();
    return token;
  }

  private HttpHeaders jsonHeaders() {
    return authedHeaders(null, MediaType.APPLICATION_JSON);
  }

  private HttpHeaders authedHeaders(String token, MediaType contentType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(contentType);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return headers;
  }

  private JsonNode json(ResponseEntity<String> response) throws Exception {
    return objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
  }

  /** `ByteArrayResource` 默认没有文件名，而 multipart 部件需要文件名才成其为"文件"。 */
  private static ByteArrayResource namedResource(byte[] content, String filename) {
    return new ByteArrayResource(content) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }
}
