package com.spe.context.system.api;

/**
 * 开放 API Token 状态（M6）：生成即 `ENABLED`，管理员停用后为 `DISABLED`。
 *
 * <p>只做单向停用（I3 的「停用」），不提供重新启用——需要继续接入时生成新 Token，
 * 旧 Token 的明文不可能从哈希恢复。
 */
public enum ApiTokenStatus {
  ENABLED,
  DISABLED
}
