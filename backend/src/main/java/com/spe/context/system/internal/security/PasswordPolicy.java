package com.spe.context.system.internal.security;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;

/**
 * 口令规则（10 §E3）：长度下限由 P1 决策定为 8（01 未规定），校验口径只此一处。
 *
 * <p>前端表单也做同样的下限提示（06 §3.2 的体验层），但**后端为准**：Web 层的 `@Size` 与
 * 本类是同一规则的两次落地——前者负责把错误定位到字段名，后者负责非 Web 入口（Service 直调）不绕过。
 */
public final class PasswordPolicy {

  public static final int MIN_LENGTH = 8;

  private PasswordPolicy() {}

  /** 不满足长度下限时抛 `PASSWORD_TOO_WEAK(2003)`。 */
  public static void validateOrThrow(String rawPassword) {
    if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
      throw new BizException(ErrorCode.PASSWORD_TOO_WEAK);
    }
  }
}
