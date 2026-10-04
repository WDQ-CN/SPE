package com.spe.context.system.internal.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.common.web.ClientIpResolver;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.common.security.AuthenticatedOperator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuditFacade} 的实现（05 §5.5 T6 / 06 §2.8）。
 *
 * <p><b>事务</b>：本类方法用默认传播（REQUIRED），调用方已有事务时**加入同一事务**——业务行与审计行
 * 同生共死；单独调用（登录、越权留痕）时自己开一个事务。
 *
 * <p><b>来源 IP</b>：走 {@link ClientIpResolver}（与登录限流共用同一处实现）。Nginx 反代下它给出的是
 * 真实客户端地址而非容器地址，前提是后端 `forward-headers-strategy=framework` **且** nginx 覆盖而非
 * 追加 `X-Forwarded-For`（03 §16 的 SEC-2，两项都已落实）。
 *
 * <p><b>脱敏</b>：只接受调用方给的业务字段，本类不碰请求体、不碰口令与令牌（06 §2.8）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditFacadeImpl implements AuditFacade {

  private final AuditLogMapper auditLogMapper;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public void record(
      AuditAction action, AuditTargetType targetType, Long targetId, Map<String, Object> detail) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Long operatorId = null;
    String operatorName = null;
    if (authentication != null && authentication.isAuthenticated()) {
      // 账号与 uid 都来自令牌（JwtAuthenticationFilter 写入的 principal），不回查用户表
      if (authentication.getPrincipal() instanceof AuthenticatedOperator operator) {
        operatorId = operator.uid();
        operatorName = operator.username();
      } else {
        operatorName = authentication.getName();
      }
    }
    recordForOperator(operatorId, operatorName, action, targetType, targetId, detail);
  }

  @Override
  @Transactional
  public void recordForOperator(
      Long operatorId,
      String operatorName,
      AuditAction action,
      AuditTargetType targetType,
      Long targetId,
      Map<String, Object> detail) {
    AuditLog entry = new AuditLog();
    entry.setOperatorId(operatorId);
    entry.setOperatorName(operatorName);
    entry.setAction(action.name());
    entry.setTargetType(targetType == null ? null : targetType.name());
    entry.setTargetId(targetId);
    entry.setDetail(writeDetail(detail));
    entry.setIp(ClientIpResolver.current());
    auditLogMapper.insert(entry);
    // 日志只记动作与目标，不记载荷（载荷可能含业务细节，06 §2.8）
    log.info("审计 {} target={}#{} by {}", action, targetType, targetId, operatorName);
  }

  /**
   * detail 转 JSON。序列化失败**抛出**而不是降级成「无 detail 的一条日志」：`detail` 由本项目代码构造，
   * 失败即缺陷；且 05 §5.5 T6 明确要求「审计写入失败则业务回滚」——悄悄丢字段会让审计看起来成功，
   * 反而违背留痕的目的（与 06 §2.6「禁止只打日志不抛出」同向）。
   */
  private String writeDetail(Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(detail);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("审计 detail 序列化失败，按 T6 回滚业务", ex);
    }
  }
}
