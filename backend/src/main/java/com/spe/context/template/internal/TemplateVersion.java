package com.spe.context.template.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.template.api.TemplateVersionStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 模板版本实体（`V7__template.sql` / 02 §3 / 05 ADR-3）：**「发布即冻结」的载体**。
 *
 * <p>模板版本化全靠这一层——维度与指标挂在版本下、任务绑定的也是版本 id，所以「发布后不可改」只需要
 * 保证版本本身不被就地修改：任何针对已发布版本的改动都会先复制出新草稿（12 §E5）。
 *
 * <p>两个字段值得说明：
 * <ul>
 *   <li>{@code copiedFromVersionId}：这份草稿复制自哪个已发布版本。用途是**并发下复用同一份草稿**——
 *       两个人同时改同一已发布版本的不同维度，应当落在同一份新草稿上，而不是各复制一份；
 *   <li>{@code publishedAt}：只有 {@code PUBLISHED} 才有值，草稿为空。
 * </ul>
 *
 * <p>`status` 落库为 {@code name()} 字符串（`VARCHAR(20)` 存大写常量，06 §4）。
 */
@Data
@TableName("template_version")
public class TemplateVersion {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long templateId;

  /** 版本号，模板内从 1 递增；`uk_template_version_no` 保证唯一。 */
  private Integer versionNo;

  private TemplateVersionStatus status;

  /** 复制来源版本 id；建档时产生的 v1 为空。 */
  private Long copiedFromVersionId;

  /** 发布时间；草稿为空。 */
  private LocalDateTime publishedAt;

  /** 乐观锁版本：发布用「状态 + 它」双守卫，草稿编辑也用它（06 §2.4 / 12 §E12）。 */
  private Integer version;
}
