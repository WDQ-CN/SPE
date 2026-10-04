package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 试算（13 §E18 的缺口端点 `POST /api/sheets/{id}/preview`）：候选值只覆盖本评分单，
 * 其它维度用已保存值（这样页面上的总分才是真实试算）。
 *
 * <p>允许多传空列表（= 用库里已保存的值试算一次）。
 */
public record PreviewScoresRequest(
    @NotNull(message = "缺少候选值")
        @Size(max = 200, message = "一次最多试算 200 个指标")
        @Valid
        List<SaveScoresRequest.Entry> scores) {}
