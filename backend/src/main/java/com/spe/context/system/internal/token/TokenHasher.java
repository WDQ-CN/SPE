package com.spe.context.system.internal.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 开放 API Token 的生成/哈希/展示前缀（M6 / 23 §2 D3）。
 *
 * <p>机器凭证是 32 字节高熵随机值，SHA-256 足够用于不可逆存储与唯一索引查找；不用 BCrypt
 * 是为了避免每个 Open API 请求都做一次慢哈希。Token 明文只在生成响应出现一次，日志/审计禁止打印。
 */
public final class TokenHasher {

  public static final String PREFIX = "spet_";

  /** `spet_` + 8 位随机串；够列表识别，不泄漏完整 Token。 */
  private static final int PREFIX_DISPLAY_LENGTH = 13;

  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenHasher() {}

  public static String generateToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String token) {
    Objects.requireNonNull(token, "token");
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      // JDK 必须提供 SHA-256；缺失属运行环境损坏，不是业务错误
      throw new IllegalStateException("JVM 不提供 SHA-256", ex);
    }
  }

  public static String displayPrefix(String token) {
    Objects.requireNonNull(token, "token");
    return token.substring(0, Math.min(PREFIX_DISPLAY_LENGTH, token.length()));
  }
}
