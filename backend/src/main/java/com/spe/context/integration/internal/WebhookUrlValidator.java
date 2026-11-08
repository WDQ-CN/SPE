package com.spe.context.integration.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * Webhook 目标的 SSRF 防线：默认拒绝回环、私网、链路本地与保留地址。
 *
 * <p>云元数据地址（169.254.169.254 / fe80::/10）无论是否允许私网目标都拒绝；
 * 本地联调可通过 {@code app.integration.webhook.allow-private-targets=true} 放开回环与 RFC1918。
 * DNS 解析与真正连接之间仍存在重绑定窗口，这里只能收敛首次解析面。
 */
@Component
public class WebhookUrlValidator {

  private final boolean allowPrivateTargets;

  public WebhookUrlValidator(WebhookProperties properties) {
    this.allowPrivateTargets = properties.allowPrivateTargets();
  }

  public void validate(String rawUrl) {
    URI uri;
    try {
      uri = new URI(rawUrl == null ? "" : rawUrl.trim());
    } catch (URISyntaxException ex) {
      throw invalid();
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getHost().isBlank()
        || uri.getUserInfo() != null) {
      throw invalid();
    }
    InetAddress[] addresses;
    try {
      // 先解析再判定：域名指向内网地址同样要拦住，不能只看字面量。
      addresses = InetAddress.getAllByName(uri.getHost());
    } catch (UnknownHostException ex) {
      throw invalid();
    }
    for (InetAddress address : addresses) {
      if (blocked(address)) {
        throw new BizException(
            ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "Webhook URL 不能指向内网或云元数据地址");
      }
    }
  }

  private boolean blocked(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    if (address instanceof Inet4Address ipv4) {
      return !allowPrivateTargets && (ipv4.isLoopbackAddress() || ipv4.isSiteLocalAddress()
          || isReservedOrCgnat(ipv4));
    }
    if (address instanceof Inet6Address) {
      byte[] bytes = address.getAddress();
      boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
      return !allowPrivateTargets && (address.isLoopbackAddress() || address.isSiteLocalAddress()
          || uniqueLocal);
    }
    return false;
  }

  private static boolean isReservedOrCgnat(Inet4Address address) {
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 0xff;
    int second = bytes[1] & 0xff;
    // 0.0.0.0/8 与 100.64.0.0/10：前者不是有效目标，后者是运营商级 NAT 地址。
    return first == 0 || (first == 100 && second >= 64 && second <= 127);
  }

  private static BizException invalid() {
    return new BizException(
        ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "Webhook URL 必须是合法的 http(s) 地址");
  }
}
