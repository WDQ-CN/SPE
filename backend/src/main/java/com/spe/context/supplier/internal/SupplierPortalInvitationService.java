package com.spe.context.supplier.internal;

import com.spe.common.security.Hashes;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.SupplierPortalUserFacade;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 门户邀请：一次性 Token（只存哈希），接受后创建门户账号（docs/erp/56 §2）。 */
@Service
@RequiredArgsConstructor
public class SupplierPortalInvitationService {

  private static final int DEFAULT_TTL_HOURS = 72;

  private final SupplierPortalInvitationMapper invitationMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final SupplierPortalUserFacade portalUserFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public InvitationCreated createInvitation(long supplierId, long operatorId, Integer ttlHours) {
    SupplierBrief supplier =
        supplierQueryFacade
            .findById(supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));
    if (!supplier.enabled()) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_FORBIDDEN, "供应商已停用，不能邀请");
    }
    int ttl = ttlHours == null ? DEFAULT_TTL_HOURS : ttlHours;
    if (ttl < 1 || ttl > 720) {
      throw new BizException(ErrorCode.BAD_REQUEST, "邀请有效期必须在 1~720 小时之间");
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    SupplierPortalInvitation invitation = new SupplierPortalInvitation();
    invitation.setSupplierId(supplierId);
    invitation.setTokenHash(Hashes.sha256Hex(token));
    invitation.setStatus(SupplierPortalInvitationStatus.PENDING);
    invitation.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(ttl));
    invitation.setCreatedBy(operatorId);
    invitationMapper.insert(invitation);
    auditFacade.record(
        AuditAction.SUPPLIER_PORTAL_INVITATION_CREATE,
        AuditTargetType.SUPPLIER_PORTAL_INVITATION,
        invitation.getId(),
        Map.of("supplierId", supplierId, "expiresAt", invitation.getExpiresAt()));
    return new InvitationCreated(token, invitation.getExpiresAt());
  }

  @Transactional
  public InvitationAccepted accept(String rawToken, String loginName, String rawPassword) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_INVITATION_INVALID);
    }
    SupplierPortalInvitation invitation =
        invitationMapper
            .lockPendingByHash(Hashes.sha256Hex(rawToken.trim()))
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_PORTAL_INVITATION_INVALID));
    if (LocalDateTime.now(ZoneOffset.UTC).isAfter(invitation.getExpiresAt())) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_INVITATION_INVALID);
    }
    SupplierBrief supplier =
        supplierQueryFacade
            .findById(invitation.getSupplierId())
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));
    if (!supplier.enabled()) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_FORBIDDEN, "供应商已停用，不能接受邀请");
    }
    long portalUserId =
        portalUserFacade.create(invitation.getSupplierId(), loginName, rawPassword);
    if (invitationMapper.markAccepted(
            invitation.getId(), portalUserId, LocalDateTime.now(ZoneOffset.UTC))
        == 0) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_INVITATION_INVALID);
    }
    auditFacade.record(
        AuditAction.SUPPLIER_PORTAL_INVITATION_ACCEPT,
        AuditTargetType.SUPPLIER_PORTAL_INVITATION,
        invitation.getId(),
        Map.of("supplierId", invitation.getSupplierId(), "portalUserId", portalUserId));
    return new InvitationAccepted(portalUserId, invitation.getSupplierId());
  }

  /** 创建结果：明文 Token 只返回这一次。 */
  public record InvitationCreated(String token, LocalDateTime expiresAt) {}

  public record InvitationAccepted(long portalUserId, long supplierId) {}
}
