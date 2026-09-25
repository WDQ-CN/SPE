package com.spe.context.system.internal.security;

import java.util.Set;

/** JWT 载荷契约（09 D6）：sub = 账号、uid、roles、dept。 */
public record JwtPayload(long uid, String username, Set<String> roles, String department) {
}
