package com.spe.context.asset.internal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 资产单号。 */
final class AssetSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private AssetSupport() {}

  static String assetNo() {
    String random =
        UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return "FA-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }
}
