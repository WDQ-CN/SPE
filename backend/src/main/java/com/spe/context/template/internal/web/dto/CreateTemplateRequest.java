package com.spe.context.template.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建模板（02 §4 `POST /api/templates`）。
 *
 * <p>长度与 `template.name VARCHAR(100)` 对齐：注解比列短是浪费、比列长则等于没拦——
 * 超长会一路走到 INSERT 再经兜底变成 5xxx（06 §2.6 的红线）。下列每个 DTO 都遵守这一条。
 *
 * <p>不带 `version`：`template` 表**没有**乐观锁列，改名与新建都不需要守卫（12 §E2 说明）。
 */
public record CreateTemplateRequest(
    @NotBlank(message = "模板名称不能为空")
        @Size(max = 100, message = "模板名称长度不得超过 100")
        String name) {}
