package com.spe.context.template.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * 维度视图（HTTP 响应投影）：带其下的指标列表，形成"维度/指标树"。
 *
 * <p>另外三个字段直接服务下游：{@code suggestedDept} 供任务发布时自动指派（01 J2）、{@code isKey} 供预警
 * 规则②（01 W1）、{@code version} 供改删的乐观锁守卫。
 */
public record DimensionView(
    long id,
    String name,
    BigDecimal weight,
    int sortNo,
    String suggestedDept,
    boolean isKey,
    int version,
    List<IndicatorView> indicators) {

  static DimensionView of(Dimension dimension, List<IndicatorView> indicators) {
    return new DimensionView(
        dimension.getId(),
        dimension.getName(),
        dimension.getWeight(),
        dimension.getSortNo() == null ? 0 : dimension.getSortNo(),
        dimension.getSuggestedDept(),
        Boolean.TRUE.equals(dimension.getIsKey()),
        dimension.getVersion() == null ? 0 : dimension.getVersion(),
        indicators);
  }
}
