package com.spe.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 错误码分段与 HTTP 状态映射锁定（09 D8 / WP4）：契约变更必须在这里体现。 */
class ErrorCodeTest {

  @Test
  @DisplayName("基线码落在约定的千位分段：1xxx 认证 / 2xxx 参数 / 3xxx 冲突 / 4xxx 权限 / 5xxx 系统")
  void baselineCodesKeepTheirSegment() {
    // 这条**不并进**下面的 everyCodeCarriesMatchingHttpStatus（测试体系审查时复核过）：
    // 那条只校验「码与 HTTP 状态自洽」，把 UNAUTHORIZED 改成 2001 并同时把状态改成 400 它照样通过——
    // 那正是语义漂移，而语义漂移会让前端的 1xxx 分流（清 token 跳登录）静默失效。
    // 这里显式钉住五个基线码的段位，成本一行、防的是一类改动。
    assertThat(ErrorCode.UNAUTHORIZED.code() / 1000).isEqualTo(1);
    assertThat(ErrorCode.BAD_REQUEST.code() / 1000).isEqualTo(2);
    assertThat(ErrorCode.CONCURRENT_CONFLICT.code() / 1000).isEqualTo(3);
    assertThat(ErrorCode.FORBIDDEN.code() / 1000).isEqualTo(4);
    assertThat(ErrorCode.INTERNAL_ERROR.code() / 1000).isEqualTo(5);
  }

  @Test
  @DisplayName("每个错误码都携带与其分段匹配的 HTTP 状态（1xxx 允许 401 与 429）")
  void everyCodeCarriesMatchingHttpStatus() {
    // 1xxx 有两个合法状态：凭据类错误 401；登录限流 429（03 §16 的 SEC-5，语义是「稍后再试」而非「凭据错误」）
    Map<Integer, Set<Integer>> allowedBySegment =
        Map.of(
            1, Set.of(401, 429),
            2, Set.of(400),
            3, Set.of(409),
            4, Set.of(403),
            5, Set.of(500));

    for (ErrorCode errorCode : ErrorCode.values()) {
      assertThat(allowedBySegment.get(errorCode.code() / 1000))
          .as("错误码 %s（%d）的 HTTP 状态", errorCode, errorCode.code())
          .contains(errorCode.httpStatus().value());
    }
  }

  @Test
  @DisplayName("错误码唯一且都有非空提示文案")
  void codesAreUniqueAndHaveMessages() {
    long distinct = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).distinct().count();
    assertThat(distinct).isEqualTo(ErrorCode.values().length);
    assertThat(ErrorCode.values()).allSatisfy(errorCode -> assertThat(errorCode.message()).isNotBlank());
  }

  @Test
  @DisplayName("Result 契约：code=0 表示成功，失败携带对应错误码")
  void resultContract() {
    assertThat(Result.ok("data").code()).isEqualTo(Result.SUCCESS_CODE);
    assertThat(Result.ok().data()).isNull();

    Result<Void> failure = Result.fail(ErrorCode.CONCURRENT_CONFLICT);
    assertThat(failure.code()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT.code());
    assertThat(failure.message()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT.message());
    assertThat(failure.data()).isNull();
  }
}
