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
 *   <li><b>旧行对应到新行的哪一行</b>（{@link #requireDimensionRow} / {@link #requireIndicatorRow}）：
 *       返回 {@link Target}（新行 id **+ 它当前的 version**）。这是"自动复制"能真正让改动生效的关键；
 *       没有它，服务端复制完就不知道该改新草稿里的哪一行——T1 的"再次修改自动复制生成新草稿"就只剩前半句。</li>
 * </ul>
 *
 * <p>⚠️ **这两条翻译方法是唯一入口，故意不接受"先自己判断、再自己取映射"的两步走法**。早先的版本把
 * `switchedWithoutMap` 与 `resolveXxx` 分开暴露，安全性全靠调用方记得先问第一步——五条叶子写路径里
 * `addIndicator` 就漏了，结果把指标的 `dimension_id` 写成了旧版本的行，静默产生一棵树上看不见、校验里
 * 不算权重、删也删不掉的脏数据（04-057）。现在只有这一个入口：行不在生效版本里就抛错。
 *
 * <p>⚠️⚠️ **判据必须"分侧"，且不许有兜底**（04-074 的 P0）：映射是按业务键配对建出来的
 * （指标用 `code`、维度用 `(sort_no, name)`），而**这些键都是可编辑字段** ⇒ 用户只要在草稿里改过名或编码，
 * 那一侧就配不上、映射就是**部分**的。下面两处旧写法合起来造成过一次**静默写穿**：
 *
 * <ul>
 *   <li>判据写成 `dimensionIdMap.isEmpty() && indicatorIdMap.isEmpty()`——**只要另一侧有映射就放行**；
 *       而复制出来的草稿几乎总有指标 ⇒ 闸门**恒不触发**；</li>
 *   <li>取值写成 `getOrDefault(requestedId, requestedId)`——配不上时**把请求里的旧行 id 当答案返回**。
 *       两者合起来：UPDATE 带着旧行 id 与旧行的 `version`（源行冻结 ⇒ 该值永不过期）**命中了已发布版本的行**
 *       ——HTTP 200，响应体还说"改动落在草稿"，审计也记在旧行上（两个独立审查者各自复现过）。</li>
 * </ul>
 *
 * <p>现在规则只剩一句：**换了版本就按行查映射，查不到直接抛 2015/2016**；没换版本时行 id 就是它自己。
 * 复制路径的映射是完备的（逐行照抄），缺项只可能意味着"这一行确实不在生效版本里"，**不该猜**。
 *
 * <p>⚠️ **守卫必须用目标行的 version，不是调用方手里源行的**（04-074 的 P1）：复用别人先建好的草稿时，
 * 目标行是**草稿里已有的那一行**，它的 `version` 可能已经不是 0 了；拿源行的版本号去守卫会让"没人动过的行"
 * 被误报 3001（实测过），反过来也可能放过一次 stale 写。所以 {@link Target} 同时带 id 与 version。
 */
public record EffectiveVersion(
    long versionId,
    int version,
    Map<Long, Target> dimensionTargets,
    Map<Long, Target> indicatorTargets) {

  /**
   * 生效版本里的一个目标行。
   *
   * @param id 生效版本里的行 id
   * @param version 该行**当前**的 `version`（乐观锁守卫要用它；新建的副本行恒为 0）
   */
  public record Target(long id, int version) {}

  /** 原本就在草稿上：版本没换、行也没换。 */
  public static EffectiveVersion sameAs(long versionId, int version) {
    return new EffectiveVersion(versionId, version, Map.of(), Map.of());
  }

  /**
   * 把调用方手里的（可能是旧版本的）维度 id 翻译成**生效版本**里的目标行。
   *
   * @param requestedVersionId 调用方以为自己在操作的那个版本（即它读出该行时所在的行版本）
   * @param fallbackVersion 调用方手里的行版本；**只在"版本没换"时**使用（此时目标行就是那一行本身）
   * @throws BizException 2015：换了版本、而这一行在生效版本里**没有对应行**（草稿是别人先复制好的，或在
   *     草稿里被改过配对键）。⚠️ **只按本侧映射判定**——不因指标侧有映射而放行。
   */
  public Target requireDimensionRow(long requestedId, long requestedVersionId, int fallbackVersion) {
    if (versionId == requestedVersionId) {
      return new Target(requestedId, fallbackVersion);
    }
    Target target = dimensionTargets.get(requestedId);
    if (target == null) {
      throw new BizException(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND);
    }
    return target;
  }

  /** 同上，指标版（2016）。 */
  public Target requireIndicatorRow(long requestedId, long requestedVersionId, int fallbackVersion) {
    if (versionId == requestedVersionId) {
      return new Target(requestedId, fallbackVersion);
    }
    Target target = indicatorTargets.get(requestedId);
    if (target == null) {
      throw new BizException(ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND);
    }
    return target;
  }

  /** 只要 id 的场景（新增行不需要守卫版本，例如 `addIndicator`）。 */
  public long requireDimensionId(long requestedId, long requestedVersionId) {
    return requireDimensionRow(requestedId, requestedVersionId, 0).id();
  }
}
