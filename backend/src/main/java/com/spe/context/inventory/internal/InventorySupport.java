package com.spe.context.inventory.internal;

import com.spe.common.security.AuthenticatedOperator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 库存单号与当前操作人。 */
final class InventorySupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private InventorySupport() {}

  static String movementNo() {
    String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return "IM-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }

  static String documentNo(String prefix) {
    String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return prefix + "-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }

  static long currentOperatorId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedOperator operator) {
      return operator.uid();
    }
    return 0L;
  }
}
