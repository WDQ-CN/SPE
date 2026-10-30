package com.spe.context.evaluation.api.openapi;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;

/**
 * 开放 API 的只读契约（M6）：进行中任务、任务指标清单。
 *
 * <p>由 evaluation.internal 实现；openapi 上下文只通过本门面读，不新增 openapi QueryMapper（05 §5.1）。
 */
public interface OpenApiQueryFacade {

  PageResult<OpenApiTaskView> activeTasks(PageQuery query);

  /** 任务不存在 → 2018；任务不在进行中 → 2020；指标来自任务绑定的已发布快照。 */
  OpenApiTaskIndicatorsView taskIndicators(long taskId);
}
