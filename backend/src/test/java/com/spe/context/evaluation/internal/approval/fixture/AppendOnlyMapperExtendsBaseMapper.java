package com.spe.context.evaluation.internal.approval.fixture;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.evaluation.internal.approval.ApprovalRecord;

/**
 * R-I 的违规夹具（{@code ArchitectureTest.appendOnlyMappersDoNotInheritBaseMapper}）：
 * 一个"只增不改表的 Mapper 却继承了 {@code BaseMapper}"的样本。
 *
 * <p>夹具放在 {@code ..internal.approval..} 包下才有效——R-I 的选取器就是这个包；
 * 正式的 {@code @AnalyzeClasses} 用 {@code DoNotIncludeTests} 排除测试类，所以它不会被真实校验看到。
 */
interface AppendOnlyMapperExtendsBaseMapper extends BaseMapper<ApprovalRecord> {}
