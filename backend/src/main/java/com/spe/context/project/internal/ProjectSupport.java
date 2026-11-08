package com.spe.context.project.internal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 项目/工时单号。 */
final class ProjectSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private ProjectSupport() {}

  static String docNo(String prefix) {
    String random =
        UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return prefix + "-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }
}
