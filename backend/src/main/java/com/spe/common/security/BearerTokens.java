package com.spe.common.security;

/** RFC 6750 Bearer 头解析：scheme 大小写不敏感，空令牌视为缺失。 */
public final class BearerTokens {

  private static final String PREFIX = "Bearer ";

  private BearerTokens() {}

  public static String extract(String header) {
    if (header == null
        || header.length() <= PREFIX.length()
        || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      return null;
    }
    return header.substring(PREFIX.length());
  }
}
