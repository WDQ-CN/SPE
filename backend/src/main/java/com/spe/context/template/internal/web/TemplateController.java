package com.spe.context.template.internal.web;

import com.spe.common.web.Result;
import com.spe.context.template.internal.TemplateService;
import com.spe.context.template.internal.TemplateSummary;
import com.spe.context.template.internal.web.dto.CreateDimensionRequest;
import com.spe.context.template.internal.web.dto.CreateIndicatorRequest;
import com.spe.context.template.internal.web.dto.CreateTemplateRequest;
import com.spe.context.template.internal.web.dto.PublishRequest;
import com.spe.context.template.internal.web.dto.RenameTemplateRequest;
import com.spe.context.template.internal.web.dto.UpdateDimensionRequest;
import com.spe.context.template.internal.web.dto.UpdateIndicatorRequest;
import com.spe.context.template.internal.web.dto.WriteResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板**写**接口（02 §4 / 01 T1~T5）：新建/改名模板、发布版本、维度与指标的增改删。
 *
 * <p><b>权限锚定 01 §7</b>：模板写**仅 ADMIN**（EVALUATOR/VIEWER 无入口，MANAGER 只读）。
 * 读接口在 {@link TemplateQueryController}——刻意拆成两个类，而不是在同一个类里用方法级
 * `@PreAuthorize` 覆盖类级：类级注解就是「默认拒绝」的边界，一个类只对应一条边界，审起来一眼能看出
 * 哪个类的**所有**方法都要求 ADMIN（06 §8 第 10 条 / ArchUnit R-H）。
 *
 * <p><b>写端点的响应统一是 {@link WriteResult}</b>：对已发布版本发起改动时服务端会自动复制出新草稿，
 * 调用方必须知道改动**实际落在哪个版本**，否则手里那份树的版本号是旧的、下次改删必被 3001 拒
 * （12 §E5 / §R-5）。
 *
 * <p>Controller 只做「入口形状与格式校验 → 调 Service → 包 Result」：业务规则（权重合计、编码唯一、
 * 状态迁移、审计）全在 {@link TemplateService}。Controller 直接注入 `AuditFacade` 会跨上下文而触
 * ArchUnit R-E 红灯（06 §2.7）。
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@RequiredArgsConstructor
public class TemplateController {

  private final TemplateService templateService;

  /** 新建模板：同时建出版本号 1 的草稿（01 T1）。 */
  @PostMapping("/templates")
  public Result<TemplateSummary> create(@Valid @RequestBody CreateTemplateRequest request) {
    return Result.ok(templateService.create(request.name()));
  }

  /**
   * 改**模板**名。版本级没有"名"（名称挂在 `template` 上，02 §3），故不提供版本改名端点。
   *
   * <p>没有 `version` 参数：`template` 表没有乐观锁列（12 §E2），这是有意为之。
   */
  @PutMapping("/templates/{id}")
  public Result<Void> rename(
      @PathVariable long id, @Valid @RequestBody RenameTemplateRequest request) {
    templateService.rename(id, request.name());
    return Result.ok();
  }

  /** 发布（01 T1/T4）：五类校验 + 守卫 UPDATE，状态或版本竞争 → 3001（12 §E12）。 */
  @PostMapping("/template-versions/{id}/publish")
  public Result<Void> publish(@PathVariable long id, @Valid @RequestBody PublishRequest request) {
    templateService.publish(id, request.version());
    return Result.ok();
  }

  /** 新增维度：`sortNo`/`isKey` 可缺省（不影响不变量，服务层补默认值）。 */
  @PostMapping("/template-versions/{id}/dimensions")
  public Result<WriteResult> addDimension(
      @PathVariable long id, @Valid @RequestBody CreateDimensionRequest request) {
    return Result.ok(
        WriteResult.of(
            templateService.addDimension(
                id,
                request.name(),
                request.weight(),
                orZero(request.sortNo()),
                request.suggestedDept(),
                orFalse(request.isKey()))));
  }

  @PutMapping("/dimensions/{id}")
  public Result<WriteResult> updateDimension(
      @PathVariable long id, @Valid @RequestBody UpdateDimensionRequest request) {
    return Result.ok(
        WriteResult.of(
            templateService.updateDimension(
                id,
                request.name(),
                request.weight(),
                orZero(request.sortNo()),
                request.suggestedDept(),
                orFalse(request.isKey()),
                request.version())));
  }

  /**
   * 删维度（连带删它的全部指标，同一事务，12 §E13）。
   *
   * <p>`version` 走 **query 参数**而不是请求体：DELETE 带 body 不在规范内，各客户端/代理行为不一致。
   */
  @DeleteMapping("/dimensions/{id}")
  public Result<WriteResult> deleteDimension(
      @PathVariable long id, @RequestParam @Min(0) int version) {
    return Result.ok(WriteResult.of(templateService.deleteDimension(id, version)));
  }

  @PostMapping("/dimensions/{id}/indicators")
  public Result<WriteResult> addIndicator(
      @PathVariable long id, @Valid @RequestBody CreateIndicatorRequest request) {
    return Result.ok(
        WriteResult.of(
            templateService.addIndicator(
                id,
                request.name(),
                request.code(),
                request.weight(),
                request.type(),
                request.scoreMode(),
                request.unit(),
                request.targetValue(),
                request.standard(),
                orZero(request.sortNo()))));
  }

  @PutMapping("/indicators/{id}")
  public Result<WriteResult> updateIndicator(
      @PathVariable long id, @Valid @RequestBody UpdateIndicatorRequest request) {
    return Result.ok(
        WriteResult.of(
            templateService.updateIndicator(
                id,
                request.name(),
                request.code(),
                request.weight(),
                request.type(),
                request.scoreMode(),
                request.unit(),
                request.targetValue(),
                request.standard(),
                orZero(request.sortNo()),
                request.version())));
  }

  @DeleteMapping("/indicators/{id}")
  public Result<WriteResult> deleteIndicator(
      @PathVariable long id, @RequestParam @Min(0) int version) {
    return Result.ok(WriteResult.of(templateService.deleteIndicator(id, version)));
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static boolean orFalse(Boolean value) {
    return Boolean.TRUE.equals(value);
  }
}
