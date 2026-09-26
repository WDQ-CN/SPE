package com.spe.context.system.api;

import java.util.Map;

/**
 * 审计 detail 的构造辅助：06 §2.8 规定了 detail 的口径 —— 只放可复述的业务字段，不含口令与令牌。
 *
 * <p>放在 `api` 包而不是某个上下文的 `internal`：detail 的写法是**跨上下文的共同约定**
 * （system 的用户服务与 supplier 的供应商服务都要按同一口径写），而 `internal` 永不外泄。
 * 它与 {@link AuditAction}／{@link AuditTargetType} 同属「审计契约」的一部分。
 */
public final class AuditDetails {

  private AuditDetails() {}

  /**
   * 只把**真正变化**的字段写进 detail，值为 `{before, after}`；值经 `String.valueOf` 归一，因此枚举、
   * 字符串、数字都能直接传。
   *
   * <p>`null` 与空串都写作空串：`Map.of` 不接受 null，而 detail 是给人看的摘要（区分 null 与 "" 无意义）。
   */
  public static void putIfChanged(
      Map<String, Object> detail, String field, Object before, Object after) {
    String beforeText = before == null ? null : String.valueOf(before);
    String afterText = after == null ? null : String.valueOf(after);
    if (beforeText == null ? afterText != null : !beforeText.equals(afterText)) {
      detail.put(
          field,
          Map.of(
              "before", beforeText == null ? "" : beforeText,
              "after", afterText == null ? "" : afterText));
    }
  }
}
