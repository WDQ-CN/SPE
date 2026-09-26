package com.spe.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 取当前请求的客户端 IP —— 审计留痕（`audit_log.ip`）与登录限流共用这一处实现。
 *
 * <p>值来自 `request.getRemoteAddr()`：Nginx 反代场景下它是**真实客户端地址**而非容器地址，前提有两条，
 * 缺一不可：① 后端开启 `server.forward-headers-strategy=framework`（application.yml 已配）；
 * ② nginx 侧用 `$remote_addr` **覆盖**（而非 `$proxy_add_x_forwarded_for` 追加）`X-Forwarded-For`，
 * 否则客户端自带的伪造值会留在最左、被后端优先采信（03 §16 的 SEC-2，已实测复现并修复）。
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
