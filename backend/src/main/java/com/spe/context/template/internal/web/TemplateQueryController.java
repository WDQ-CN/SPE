package com.spe.context.template.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.template.api.PublishedVersionBrief;
import com.spe.context.template.api.TemplateQueryFacade;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import com.spe.context.template.internal.TemplateService;
import com.spe.context.template.internal.TemplateSummary;
import com.spe.context.template.internal.TemplateVersionTree;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板**读**接口（02 §4）：列表、版本详情、快照、已发布版本清单。
 *
 * <p>与 {@link TemplateController} 拆成两个类的理由见那边：**一个类对应一条权限边界**。
 * 这里是 `ADMIN + MANAGER`——01 §7 矩阵写的是「评估模板管理｜ADMIN ✓｜MANAGER **查看**｜EVALUATOR –｜
 * VIEWER –」，即 MANAGER 有只读视图（08 §2 也要求），而 12 §E7 据此修正了 02 §5 只写 ADMIN 的口径。
 *
 * <p>越权由后端 4xxx 兜底并在 system 上下文留 `ACCESS_DENIED`，不依赖前端隐藏入口。
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class TemplateQueryController {

  private final TemplateService templateService;
  private final TemplateQueryFacade templateQueryFacade;
  private final TemplateSnapshotFacade templateSnapshotFacade;

  /** 模板列表（12 §E18 的展示字段：版本数 + 最新已发布版本号 + 是否有草稿）。 */
  @GetMapping("/templates")
  public Result<PageResult<TemplateSummary>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
    return Result.ok(templateService.page(PageQuery.of(page, size)));
  }

  /** 版本详情：维度/指标整棵树（编辑器用）。`page`/`size` 不适用——整棵树一次取回。 */
  @GetMapping("/template-versions/{id}")
  public Result<TemplateVersionTree> detail(@PathVariable long id) {
    return Result.ok(templateService.detail(id));
  }

  /**
   * 已发布版本的不可变快照（05 ADR-3）。
   *
   * <p>走 `TemplateSnapshotFacade.requirePublished`：目标不存在或**尚未发布**都返回 2010。
   * 这条端点让"计分口径只来自冻结过的数据"在 HTTP 层也可验证。
   */
  @GetMapping("/template-versions/{id}/snapshot")
  public Result<TemplateSnapshot> snapshot(@PathVariable long id) {
    return Result.ok(templateSnapshotFacade.requirePublished(id));
  }

  /**
   * 某模板的已发布版本清单（供 P3 建任务时**选版本**，01 J1）。
   *
   * <p>与 P3 内部调用共用同一个门面方法，避免"页面看到的版本"与"后端能绑定的版本"两套口径。
   */
  @GetMapping("/templates/{id}/published-versions")
  public Result<List<PublishedVersionBrief>> publishedVersions(@PathVariable long id) {
    return Result.ok(templateQueryFacade.publishedVersions(id));
  }
}
