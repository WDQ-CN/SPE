package com.spe.context.system.internal.audit;

import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 越权访问留痕（05 §9 Q8 / 06 §2.8 的 `ACCESS_DENIED` / 10 §E15）。
 *
 * <p>Spring Security 在授权被拒时会发布 {@link AuthorizationDeniedEvent}，本监听器据此落一条审计。
 * 放在 system 上下文而不是 `common`：审计写入需要 `AuditFacade`，而 `common` 不得依赖任何业务上下文
 * （ArchUnit R-F），`GlobalExceptionHandler` 因此只负责转 `Result`、不负责留痕。
 *
 * <p><b>只记「已登录但被拒」</b>：未携带令牌的请求也会走到拒绝分支（认证为匿名），
 * 那种 401 没有可溯源的操作人，记下来只会污染日志；匿名请求由 `RestAuthenticationEntryPoint` 返回 401。
 *
 * <p>本方法不标注 `@Transactional`：越权时没有业务写，留痕是独立的一次追加写入（`AuditFacade` 自己开事务）。
 * 留痕失败**不吞异常**（06 §2.3）：此时请求会以 5xxx 结束而不是 403——审计不可用属系统级故障，
 * 宁可让告警响起来，也不静默丢掉一条越权记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessDeniedAuditListener {

  private final AuditFacade auditFacade;

  @EventListener
  public void onAuthorizationDenied(AuthorizationDeniedEvent<?> event) {
    var authentication = event.getAuthentication().get();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return;
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      HttpServletRequest request = attributes.getRequest();
      detail.put("method", request.getMethod());
      // 只记路径不记请求体（06 §2.8：禁止完整请求体）
      detail.put("uri", request.getRequestURI());
    }
    auditFacade.record(AuditAction.ACCESS_DENIED, null, null, detail.isEmpty() ? null : detail);
  }
}
