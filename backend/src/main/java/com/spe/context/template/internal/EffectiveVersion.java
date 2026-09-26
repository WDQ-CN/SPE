package com.spe.context.template.internal;

import java.util.Map;

/**
 * 「这次写操作实际生效的版本」（12 §E5 / §R-13 / §R-13a）。
 *
 * <p>存在的理由：对**已发布**版本发起改动时，服务端会复制出新草稿再改，调用方手里的 `versionId` 与**行 id**
 * 同时过期了——复制出来的是新行，维度与指标的 id 都变了、`version` 重置为 0。因此这个返回值要能回答三个问题：
 *
 * <ul>
 *   <li><b>落在哪个版本</b>（{@code versionId}）：不等于请求时那个就说明换了版本，前端必须**重新拉整棵树**；
 *       拿旧 id 继续改只会得到 2015/2016（不存在）或 3001；</li>
 *   <li><b>旧行对应到新行的哪个 id</b>（两个 map + {@code resolveXxx}）：这是"自动复制"能真正让改动生效的关键。
 *       没有它，服务端复制完就不知道该改新草稿里的哪一行——T1 的"再次修改自动复制生成新草稿"就只剩前半句；</li>
 *   <li><b>要不要留复制痕迹</b>（{@code copied}）：只在**本次新建**草稿时为真；复用一个已存在的同源草稿时不算
 *       "发生了复制"，不该写 {@code TEMPLATE_VERSION_AUTO_COPY} 审计。</li>
 * </ul>
 *
 * <p>⚠️ **复用场景要特别处理**：如果草稿是**别人**先复制好的（{@code versionId != 请求的 id} 且 {@code copied}
 * 为假），旧版本里的行在新草稿里没有对应关系——此时"改某一行"无法翻译，调用方应当报 2015/2016，而不是猜。
 * 判据见 {@link #switchedWithoutMap}。
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

  /** 把调用方手里的（旧版本的）行 id 翻译成实际生效版本里的 id；没记录则原样返回。 */
  public long resolveDimension(long requestedId) {
    return dimensionIdMap.getOrDefault(requestedId, requestedId);
  }

  public long resolveIndicator(long requestedId) {
    return indicatorIdMap.getOrDefault(requestedId, requestedId);
  }

  /**
   * 是否"目标已发布、但草稿是别人先复制好的"——此时没有 id 映射，"改/删某一行"无法定位，调用方应报不存在
   * （2015/2016）让用户刷新后重做，而不是把改动落到猜测的行上。
   */
  public boolean switchedWithoutMap(long requestedVersionId) {
    return versionId != requestedVersionId && !copied;
  }
}
