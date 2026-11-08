package com.spe.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 共享的 SHA-256 与十六进制编码：令牌、幂等键、邀请与 Webhook 签名共用这一处实现。 */
public final class Hashes {

  private Hashes() {}

  public static String sha256Hex(String value) {
    return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256Hex(byte[] value) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("JVM 不提供 SHA-256", ex);
    }
  }

  public static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }
}
