package com.spe.context.template.internal;

import lombok.Data;

/**
 * 模板列表的**聚合查询行**（12 §R-15）。
 *
 * <p>为什么是普通类而不是 record：MyBatis 默认按「无参构造 + setter」映射结果集，record 需要额外的构造器
 * 映射配置。项目里 record 一律是**手工转换**的读模型（如 `SupplierSummary`）——这里沿用同一分法：
 * Mapper 出 {@link TemplateRow}，Service 再转成对外读模型。
 *
 * <p>字段用包装类型：`LEFT JOIN` 下没有版本的模板，聚合列会是 {@code NULL}（`COUNT` 除外）。
 */
@Data
public class TemplateRow {

  private Long id;

  private String name;

  /** 版本数（含草稿与已发布）。 */
  private Long versionCount;

  /** 草稿数；> 0 表示"当前有未发布的改动"。 */
  private Long draftCount;

  /** 最新**已发布**版本号；没有任何已发布版本时为 {@code null}。 */
  private Integer latestPublishedNo;

  /** 最新版本（不分状态）的 id，供前端直接进入编辑器；没有任何版本时为 {@code null}。 */
  private Long latestVersionId;
}
