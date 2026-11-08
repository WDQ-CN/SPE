package com.spe.context.procurement.internal;

import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** P2P 的文本/数值/单据号规范化，避免各 Service 各写一套。 */
final class P2pSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final BigDecimal PRICE_TOLERANCE_RATE = new BigDecimal("0.01");
  private static final BigDecimal PRICE_TOLERANCE_MIN = new BigDecimal("0.01");

  /** DECIMAL(19,4) 的上限；数量×单价可能在应用层溢出，必须在 INSERT 前翻成 2xxx。 */
  static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

  private P2pSupport() {}

  static String trim(String value) {
    return value == null ? null : value.trim();
  }

  static String upper(String value) {
    String trimmed = trim(value);
    return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  static String keyword(String value) {
    String trimmed = trim(value);
    return trimmed == null || trimmed.isEmpty() ? null : SqlKeywords.escapeLike(trimmed);
  }

  static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
    String normalized = upper(value);
    if (normalized == null || normalized.isEmpty()) {
      return null;
    }
    try {
      return Enum.valueOf(type, normalized);
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, label + "不合法：" + value);
    }
  }

  static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
  }

  static BigDecimal price(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
  }

  static BigDecimal lineAmount(BigDecimal qty, BigDecimal price) {
    return qty.multiply(price).setScale(4, RoundingMode.HALF_UP);
  }

  static void ensureAmount(BigDecimal amount, ErrorCode code, String label) {
    if (amount.compareTo(MAX_AMOUNT) > 0) {
      throw new BizException(code, label + "超过金额上限");
    }
  }

  static BigDecimal sum(BigDecimal left, BigDecimal right) {
    return (left == null ? BigDecimal.ZERO : left).add(right);
  }

  /**
   * `PR-20261107-1A2B3C4D5E6F`：日期 + 12 位随机段，避免引入额外的序列表与并发争用。
   *
   * <p>6 位十六进制在 5k 张单据时生日碰撞概率已过半；12 位（48 bit）足以覆盖 E1 规模。
   */
  static String docNo(String prefix) {
    String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return prefix + "-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }

  /** 允许 `price <= reference + max(reference*1%, 0.01)`；没有价目表（reference 为空）时不做约束。 */
  static boolean withinPriceTolerance(BigDecimal price, BigDecimal reference) {
    if (reference == null) {
      return true;
    }
    BigDecimal allowed =
        reference.multiply(PRICE_TOLERANCE_RATE).max(PRICE_TOLERANCE_MIN);
    return price.compareTo(reference.add(allowed)) <= 0;
  }

  /** 序列号规则唯一实现点：序列物料数量必须为整数且与序列数一致；非序列物料不得带序列。 */
  static java.util.List<String> serials(
      boolean serialControlled, BigDecimal qty, java.util.List<String> rawSerials) {
    if (!serialControlled) {
      if (rawSerials != null && !rawSerials.isEmpty()) {
        throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED, "非序列管理物料不能指定序列号");
      }
      return java.util.List.of();
    }
    if (rawSerials == null || rawSerials.isEmpty()) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED);
    }
    java.util.List<String> serials =
        rawSerials.stream()
            .map(value -> value == null ? "" : value.trim())
            .filter(value -> !value.isEmpty())
            .toList();
    if (serials.size() != rawSerials.size()
        || qty.stripTrailingZeros().scale() > 0
        || BigDecimal.valueOf(serials.size()).compareTo(qty) != 0) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED);
    }
    if (new java.util.HashSet<>(serials).size() != serials.size()) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE);
    }
    return serials;
  }

  static String required(String value, int maxLength, String label) {
    String trimmed = trim(value);
    if (trimmed == null || trimmed.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
    }
    if (trimmed.length() > maxLength) {
      throw new BizException(ErrorCode.BAD_REQUEST, label + "长度不得超过 " + maxLength);
    }
    return trimmed;
  }

  static String optional(String value, int maxLength, String label) {
    String trimmed = trim(value);
    if (trimmed == null || trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > maxLength) {
      throw new BizException(ErrorCode.BAD_REQUEST, label + "长度不得超过 " + maxLength);
    }
    return trimmed;
  }
}
