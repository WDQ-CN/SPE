package com.spe.context.governance.internal;

/** SoD 冲突结果。 */
public record SodConflictView(
    long ruleId, String code, String roleA, String roleB, String description, boolean excepted) {}
