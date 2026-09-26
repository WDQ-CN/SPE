package com.spe.context.template.api;

import java.util.Optional;

/**
 * 模板快照门面（05 §5.1 / 05 ADR-3）：**已发布版本**不可变快照的唯一出口；P4 定稿计分时经它取数，
 * 快照 DTO 即 {@code ScoreCalculator} 的输入。
 *
 * <p>只认已发布版本：传草稿 id 一律按"没有这个快照"处理，而不是返回半成品——计分口径必须来自冻结过的
 * 数据。这与 12 §E5 是同一个判断：改动只能落在草稿或新版本上，永远落不到已发布版本上。
 */
public interface TemplateSnapshotFacade {

  /** 取已发布版本的快照；版本不存在、或存在但不是 {@code PUBLISHED} 时返回空。 */
  Optional<TemplateSnapshot> findPublished(long versionId);

  /** 同上，但取不到时抛业务异常——供"必须拿到快照才能继续"的路径使用（P4 定稿）。 */
  TemplateSnapshot requirePublished(long versionId);
}
