package com.spe.context.template.internal.web.dto;

import com.spe.common.validation.Limits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改**模板**名（02 §4 `PUT /api/templates/{id}`）。
 *
 * <p>与 {@link CreateTemplateRequest} 形状相同但语义不同，故不合并：版本级没有"名"（名称挂在
 * `template` 上，02 §3），也**不为了对称造一个无意义的版本改名端点**（12 §3-W3）。
 * 同样不带 `version`——原因见 {@link CreateTemplateRequest}。
 */
public record RenameTemplateRequest(
    @NotBlank(message = "模板名称不能为空")
        @Size(max = Limits.NAME_100, message = "模板名称长度不得超过 100")
        String name) {}
