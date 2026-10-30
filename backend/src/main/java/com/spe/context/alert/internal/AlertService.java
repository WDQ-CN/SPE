package com.spe.context.alert.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警列表与处理（01 W3；M5 W2）。
 *
 * <p>面向用户的列表只含 UNREAD/HANDLED（SUPERSEDED 只在重算/解锁时出现）；处理是守卫 UPDATE，
 * 0 行后复读定夺：不存在 2032、非 UNREAD 2033（alert 无 version 列，并发后到者同样落 2033）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

  private static final int COMMENT_MAX_LENGTH = 500;

  private final AlertMapper alertMapper;

  @Transactional(readOnly = true)
  public PageResult<AlertView> page(PageQuery query, String status) {
    String filter = normalizeStatus(status);
    long total = alertMapper.countPage(filter);
    if (total == 0) {
      return new PageResult<>(List.of(), 0, query.page(), query.size());
    }
    List<AlertView> rows =
        alertMapper.findPage(filter, query.offset(), query.size()).stream()
            .map(AlertView::of)
            .toList();
    return new PageResult<>(rows, total, query.page(), query.size());
  }

  /** 处理：备注可选、trim、≤500；只有 UNREAD 能处理。 */
  @Transactional
  public AlertView handle(long alertId, long operatorId, String comment) {
    String normalized = comment == null ? null : comment.trim();
    if (normalized != null && normalized.isEmpty()) {
      normalized = null;
    }
    if (normalized != null && normalized.length() > COMMENT_MAX_LENGTH) {
      throw new BizException(ErrorCode.BAD_REQUEST, "处理备注长度不得超过 500 字");
    }
    int rows = alertMapper.handle(alertId, operatorId, normalized);
    if (rows == 0) {
      Alert existing = alertMapper.selectById(alertId);
      if (existing == null) {
        throw new BizException(ErrorCode.ALERT_NOT_FOUND);
      }
      throw new BizException(
          ErrorCode.ALERT_STATUS_INVALID, "预警当前状态不允许处理：" + existing.getStatus());
    }
    return alertMapper
        .findViewById(alertId)
        .map(AlertView::of)
        .orElseThrow(() -> new BizException(ErrorCode.ALERT_NOT_FOUND));
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String value = status.trim().toUpperCase();
    if ("SUPERSEDED".equals(value)) {
      // 已取代的预警不对外列出（22 §2 D2）：显式传它也不放行，避免前端以为能筛
      throw new BizException(ErrorCode.BAD_REQUEST, "已取代的预警不对外列出");
    }
    if (!"UNREAD".equals(value) && !"HANDLED".equals(value)) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预警状态不合法：" + status);
    }
    return value;
  }
}
