package com.spe.context.integration.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.web.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Webhook 目标 SSRF 校验（默认拒绝私网；云元数据始终拒绝）。 */
class WebhookUrlValidatorTest {

  @Test
  @DisplayName("默认配置拒绝回环、私网、链路本地与非法 scheme")
  void rejectsPrivateTargetsByDefault() {
    WebhookUrlValidator validator = new WebhookUrlValidator(new WebhookProperties(false));

    assertThatThrownBy(() -> validator.validate("http://127.0.0.1/hook"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> validator.validate("http://10.0.0.1/hook"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> validator.validate("ftp://example.com/hook"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> validator.validate("https://user:pass@example.com/hook"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("允许私网时放行回环，但云元数据仍然拒绝")
  void allowsPrivateButNeverMetadata() {
    WebhookUrlValidator validator = new WebhookUrlValidator(new WebhookProperties(true));

    assertThatCode(() -> validator.validate("http://127.0.0.1:8080/hook"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("公网字面量地址放行")
  void allowsPublicAddress() {
    assertThatCode(() -> new WebhookUrlValidator(new WebhookProperties(false)).validate("https://8.8.8.8/hook"))
        .doesNotThrowAnyException();
  }
}
