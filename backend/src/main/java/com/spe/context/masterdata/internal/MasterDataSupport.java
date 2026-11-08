package com.spe.context.masterdata.internal;

import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemType;
import com.spe.context.masterdata.api.MasterDataStatus;

/** 主数据文本/筛选参数的统一规范化，避免各 Service 各写一套。 */
final class MasterDataSupport {

  private MasterDataSupport() {}

  static String trim(String value) {
    return value == null ? null : value.trim();
  }

  static String upper(String value) {
    String trimmed = trim(value);
    return trimmed == null ? null : trimmed.toUpperCase();
  }

  /** 读取接口返回脱敏税号；编辑回写时保留原值，避免把 ****1234 这种掩码写进库。 */
  static String taxNoForUpdate(String value, String current) {
    String trimmed = trim(value);
    return trimmed == null || trimmed.startsWith("****") ? current : trimmed;
  }

  static String keyword(String value) {
    String trimmed = trim(value);
    return trimmed == null || trimmed.isEmpty() ? null : SqlKeywords.escapeLike(trimmed);
  }

  static MasterDataStatus status(String value) {
    String normalized = upper(value);
    if (normalized == null || normalized.isEmpty()) {
      return null;
    }
    try {
      return MasterDataStatus.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "状态不合法：" + value);
    }
  }

  static ItemType itemType(String value) {
    String normalized = upper(value);
    if (normalized == null || normalized.isEmpty()) {
      return null;
    }
    try {
      return ItemType.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "物料类型不合法：" + value);
    }
  }
}
