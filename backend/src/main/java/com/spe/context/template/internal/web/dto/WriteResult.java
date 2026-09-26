package com.spe.context.template.internal.web.dto;

import com.spe.context.template.internal.EffectiveVersion;

/**
 * 模板写端点的统一响应（12 §E5 / §R-5 / 出口判据 4）：告诉调用方改动**实际落在哪个版本**。
 *
 * <p><b>为什么两个字段都要</b>：对**已发布**版本发起改动时，服务端会在同一事务里复制出新草稿再改。
 * 此时只回 `effectiveVersionId` 是不够的——前端手里那份树的 `version` 是**旧版本**读出来的，
 * 下一次改删会被 3001 拒掉（这就是 R-5 描述的失败）。
 *
 * <ul>
 *   <li>{@code effectiveVersionId} 与请求里的版本不同 → 发生了自动复制，前端应按新 id **重新拉整棵树**
 *       （行 id 全变了）；</li>
 *   <li>相同 → 只是原地更新，把 {@code effectiveVersion} 写回手里的树即可。</li>
 * </ul>
 *
 * <p>刻意**不**回传"旧行 id → 新行 id"的映射：那是服务端的内部细节，前端按新版本 id 重新拉一次就能
 * 拿到新行 id；少一个需要双方保持一致性的协议面（06 §2.8 的取向）。
 */
public record WriteResult(long effectiveVersionId, int effectiveVersion) {

  /** 从服务层的 {@link EffectiveVersion} 投影而来（实体与内部映射都不出上下文）。 */
  public static WriteResult of(EffectiveVersion effective) {
    return new WriteResult(effective.versionId(), effective.version());
  }
}
