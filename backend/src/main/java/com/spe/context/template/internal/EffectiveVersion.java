package com.spe.context.template.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.util.Map;

/**
 * 「这次写操作实际生效的版本」（12 §E5 / §R-13 / §R-13a）。
 *
 * <p>存在的理由：对**已发布**版本发起改动时，服务端会复制出新草稿再改，调用方手里的 `versionId` 与**行 id**
 * 同时过期了——复制出来的是新行，维度与指标的 id 都变了、`version` 重置为 0。因此这个返回值要能回答三个问题：
 *
 * <ul>
 *   <li><b>落在哪个版本</b>（{@code versionId}）：不等于请求时那个就说明换了版本，调用方必须**重新拉整棵树**；
 *       拿旧 id 继续改只会得到 2015/2016（不存在）或 3001；</li>
 *   <li><b>旧行对应到新行的哪个 id</b>（{@link #requireDimensionId} / {@link #requireIndicatorId}）：这是
 *       "自动复制"能真正让改动生效的关键。没有它，服务端复制完就不知道该改新草稿里的哪一行——T1 的
 *       "再次修改自动复制生成新草稿"就只剩前半句；</li>
 *   <li><b>要不要留复制痕迹</b>（{@code copied}）：只在**本次新建**草稿时为真；复用一个已存在的同源草稿时
 *       不算"发生了复制"，不该写 {@code TEMPLATE_VERSION_AUTO_COPY} 审计。</li>
 * </ul>
 *
 * <p>⚠️ **这两条翻译方法是唯一入口，故意不接受"先自己判断、再自己取映射"的两步走法**。早先的版本把
 * `switchedWithoutMap` 与 `resolveXxx` 分开暴露，安全性全靠调用方记得先问第一步——五条叶子写路径里
 * `addIndicator` 就漏了，结果把指标的 `dimension_id` 写成了旧版本的行，静默产生一棵树上看不见、校验里
 * 不算权重、删也删不掉的脏数据（04-057）。现在只有这一个入口：行不在生效版本里就抛错。
 */
public record EffectiveVersion(
    long versionId,
    int version,
    boolean copied,
    Map<Long, Long> dimensionIdMap,
    Map<Long, Long> indicatorIdMap) {

  /** 原本就在草稿上：版本没换、行也没换。 */
  public static EffectiveVersion sameAs(long versionId, int version) {
    return new EffectiveVersion(versionId, version, false, Map.of(), Map.of());
  }

  /**
   * 把调用方手里的（可能是旧版本的）维度 id 翻译成**生效版本**里的 id。
   *
   * @param requestedVersionId 调用方以为自己在操作的那个版本（即它读出该行时所在的行版本）
   * @throws BizException 2015：那一行不在生效版本里（草稿是别人先复制好的，没有映射可用）
   */
  public long requireDimensionId(long requestedId, long requestedVersionId) {
    requireMapped(requestedVersionId, ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND);
    return dimensionIdMap.getOrDefault(requestedId, requestedId);
  }

  /** 同上，指标版（2016）。 */
  public long requireIndicatorId(long requestedId, long requestedVersionId) {
    requireMapped(requestedVersionId, ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND);
    return indicatorIdMap.getOrDefault(requestedId, requestedId);
  }

  /**
   * 是否"目标已发布、但草稿是别人先复制好的"——此时没有 id 映射，"改/删某一行"无法定位。
   *
   * <p>一般不需要直接用它：走 {@link #requireDimensionId} / {@link #requireIndicatorId} 即可。
   */
  public boolean switchedWithoutMap(long requestedVersionId) {
    return versionId != requestedVersionId && !copied;
  }

  private void requireMapped(long requestedVersionId, ErrorCode errorCode) {
    if (switchedWithoutMap(requestedVersionId)) {
      throw new BizException(errorCode);
    }
  }
}
