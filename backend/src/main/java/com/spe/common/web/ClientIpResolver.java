package com.spe.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 取当前请求的客户端 IP —— 审计留痕（`audit_log.ip`）与登录限流共用这一处实现。
 *
 * <p>值来自 `request.getRemoteAddr()`。⚠️ **本机的拓扑决定了它拿不到真实客户端 IP**
 * （03 §38 的 P1-4/P1-5，2026-10-17 改判）：单机 Docker Compose 下经 nginx 的请求在容器侧
 * 恒为网桥地址，而 `application.yml` 已把 `forward-headers-strategy` 设为 `none`——
 * 这是**故意的**：早先采信 XFF 时，任何能直连后端的人都能把任意值写进 `audit_log.ip`
 * 并绕过 IP 维度的登录限流（实测 25 个伪造 IP × 25 个账号 ⇒ 0 次 429），而换来的"溯源"在
 * 这个拓扑下本来就不成立。`nginx.conf` 里覆盖 XFF 的那一行因此**不再被采信**（留着是为了
 * 迁移到有真实源 IP 的环境时一并启用，见 11 §9 的登记）。
 *
 * <p>没有请求上下文时（定时任务、单测直调 Service）返回 `null`：调用方要容忍空值，
 * **不要在这里编造一个假地址**——假 IP 会让审计与限流同时失真。
 */
public final class ClientIpResolver {

  private ClientIpResolver() {}

  public static String current() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      HttpServletRequest request = attributes.getRequest();
      return request.getRemoteAddr();
    }
    return null;
  }
}
