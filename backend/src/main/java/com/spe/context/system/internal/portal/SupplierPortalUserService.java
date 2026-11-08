package com.spe.context.system.internal.portal;

import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.internal.security.PasswordPolicy;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 门户账号管理（内部管理员创建）与请求期状态校验。 */
@Service
@RequiredArgsConstructor
public class SupplierPortalUserService {

  private final SupplierPortalUserMapper userMapper;
  private final PasswordEncoder passwordEncoder;
  private final AuditFacade auditFacade;

  @Transactional
  public SupplierPortalUserView create(long supplierId, String loginName, String rawPassword) {
    String normalizedLogin = loginName == null ? null : loginName.trim();
    if (normalizedLogin == null || normalizedLogin.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "门户登录名不能为空");
    }
    PasswordPolicy.validateOrThrow(rawPassword);
    SupplierPortalUser user = new SupplierPortalUser();
    user.setSupplierId(supplierId);
    user.setLoginName(normalizedLogin);
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setStatus(SupplierPortalUserStatus.ACTIVE);
    user.setFailedAttempts(0);
    user.setVersion(0);
    try {
      userMapper.insert(user);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_LOGIN_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("supplierId", supplierId);
    detail.put("loginName", normalizedLogin);
    auditFacade.record(
        AuditAction.SUPPLIER_PORTAL_USER_CREATE,
        AuditTargetType.SUPPLIER_PORTAL_USER,
        user.getId(),
        detail);
    return new SupplierPortalUserView(
        user.getId(), supplierId, null, null, normalizedLogin, user.getStatus());
  }

  @Transactional(readOnly = true)
  public SupplierPortalUser validateForRequest(long portalUserId) {
    SupplierPortalUser user = userMapper.findById(portalUserId).orElse(null);
    if (user == null || user.getStatus() != SupplierPortalUserStatus.ACTIVE) {
      return null;
    }
    return user;
  }

  @Transactional(readOnly = true)
  public SupplierPortalUser findById(long id) {
    return userMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_PORTAL_USER_NOT_FOUND));
  }
}
