package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 口令长度规则（10 §E3）：边界 7/8 位，锚定决策值而不是「看起来合理」。 */
class PasswordPolicyTest {

  @Test
  @DisplayName("7 位被拒、8 位通过（E3 定的下限）")
  void minLengthBoundary() {
    assertThatThrownBy(() -> PasswordPolicy.validateOrThrow("1234567"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.PASSWORD_TOO_WEAK);

    assertThatCode(() -> PasswordPolicy.validateOrThrow("12345678")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("空口令按过短处理，不抛 NPE")
  void nullAndBlankAreRejected() {
    assertThatThrownBy(() -> PasswordPolicy.validateOrThrow(null)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> PasswordPolicy.validateOrThrow("")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("下限常量与 06 §2.6 的错误码文案一致（改一处时另一处会红）")
  void minLengthMatchesMessage() {
    assertThat(PasswordPolicy.MIN_LENGTH).isEqualTo(8);
    assertThat(ErrorCode.PASSWORD_TOO_WEAK.message()).contains("8");
  }
}
