package com.spe.context.template.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import com.spe.context.template.api.TemplateVersionStatus;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模板服务（01 T1~T5 / 12 §3 W2）。
 *
 * <p><b>三条不变量</b>（写这个类时最该守住的东西）：
 * <ol>
 *   <li><b>已发布版本不可改</b>（01 §6.4 / 05 ADR-3）：任何针对已发布版本的改动都必须先落到新草稿上，
 *       由 {@link #ensureEditable} 统一保证——它不是"每个写方法各自判断"，而是所有写路径的公共入口。
 *       复制发生时它同时把**旧行 id → 新行 id** 的映射带回来，否则"自动复制"就只是复制、改动落不到地方；</li>
 *   <li><b>并发复制不能产生平行草稿</b>（12 §E12）：复制前先锁模板行，再复用同源草稿；唯一键只兜底
 *       "同一版本号插两次"；</li>
 *   <li><b>业务失败不留成功痕迹</b>：所有审计都写在守卫 UPDATE 成功之后。</li>
 * </ol>
 *
 * <p><b>事务形态</b>：每个公开写方法都是单一 `@Transactional`，多表写（复制整棵树、删维度连带删指标）与
 * 审计在同一事务内（05 §5.5 T6/T7/T8）。{@link #ensureEditable} 虽然是 public、也标了 `@Transactional`，
 * 但**总是被同类方法调用**，因此实际没有独立事务边界——Spring 的代理不拦截自调用，这一点是刻意的：
 * 写操作与"确保可编辑"必须同生共死。
 *
 * <p><b>跨上下文</b>：本类依赖 `system.api.AuditFacade`（06 §2.8 要求模板变更留痕），依赖方向由
 * ArchUnit R-A 守护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

  /** 权重合计的目标值。用 `compareTo` 比较——`DECIMAL` 读出来是 `100.00`，`equals` 会因为 scale 不等判假。 */
  private static final BigDecimal FULL_WEIGHT = new BigDecimal("100");

  private final TemplateMapper templateMapper;
  private final TemplateVersionMapper templateVersionMapper;
  private final DimensionMapper dimensionMapper;
  private final IndicatorMapper indicatorMapper;
  private final AuditFacade auditFacade;

  // ------------------------------------------------------------------ 读

  /** 模板分页列表：一条聚合查询取回全部展示字段（12 §R-15），不做逐行回查版本。 */
  @Transactional(readOnly = true)
  public PageResult<TemplateSummary> page(PageQuery query) {
    List<TemplateSummary> rows =
        templateMapper.findPage(query.offset(), query.size()).stream()
            .map(TemplateSummary::of)
            .toList();
    return PageResult.of(rows, templateMapper.countAll(), query.page(), query.size());
  }

  /**
   * 版本详情：编辑器要的整棵树，**三条查询**（版本、维度、指标各一条，内存里按维度分组）。
   *
   * <p>不硬凑成两条：三层结构摆在那里，把指标塞进维度的 `IN` 查询只会让 SQL 难读、收益为零。关键是按
   * 外键批量取，不做"逐维度查指标"那种 N+1。
   */
  @Transactional(readOnly = true)
  public TemplateVersionTree detail(long versionId) {
    TemplateVersion version = requireVersion(versionId);
    Template template =
        templateMapper.findById(version.getTemplateId())
            .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND));

    List<Dimension> dimensions = dimensionMapper.findByVersionId(versionId);
    Map<Long, List<Indicator>> byDimension =
        indicatorMapper.findByVersionId(versionId).stream()
            .collect(Collectors.groupingBy(Indicator::getDimensionId));

    List<DimensionView> tree =
        dimensions.stream()
            .map(
                d ->
                    DimensionView.of(
                        d,
                        byDimension.getOrDefault(d.getId(), List.of()).stream()
                            .map(IndicatorView::of)
                            .toList()))
            .toList();

    return new TemplateVersionTree(
        template.getId(),
        template.getName(),
        version.getId(),
        version.getVersionNo(),
        version.getStatus(),
        version.getVersion(),
        tree);
  }

  // ------------------------------------------------------------------ 模板

  /**
   * 新建模板：同时建出版本号 1 的草稿（01 T1 的"草稿可编辑"）。
   *
   * <p>`template_version` 的插入也要翻译唯一键冲突——`UK(template_id, version_no)` 的写路径有三条
   * （建模板、自动复制、将来的 T6），**漏掉任何一条**都会让冲突经兜底变成 5xxx，这正是 04-030 的教训。
   */
  @Transactional
  public TemplateSummary create(String name) {
    requireText(name, 100, "模板名称");
    Template template = new Template();
    template.setName(name);
    templateMapper.insert(template);

    TemplateVersion version = new TemplateVersion();
    version.setTemplateId(template.getId());
    version.setVersionNo(1);
    version.setStatus(TemplateVersionStatus.DRAFT);
    version.setVersion(0);
    insertVersionOrConflict(version);

    auditFacade.record(
        AuditAction.TEMPLATE_CREATE, AuditTargetType.TEMPLATE, template.getId(), Map.of("name", name));
    log.info("创建模板 {}（id={}，草稿版本 id={}）", name, template.getId(), version.getId());

    return new TemplateSummary(template.getId(), name, 1, 1, null, version.getId());
  }

  /**
   * 改模板名。
   *
   * <p>⚠️ **这条没有乐观锁守卫**，是有意为之：`V7` 只给三张子表建了 `version` 列，`template` 表没有——
   * 改的是同一个名字字段、风险极低，为它单独加一列与一次守卫不值得（12 §E2 的"不替需求加约束"取向）。
   * 真正需要守卫的是**版本内容**，那些路径全都带 `version`。
   */
  @Transactional
  public void rename(long templateId, String name) {
    requireText(name, 100, "模板名称");
    Template current =
        templateMapper.findById(templateId)
            .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND));
    if (current.getName().equals(name)) {
      return; // 无变化就不写审计（与 AuditDetails.putIfChanged 的"只记真正变化"同口径）
    }
    templateMapper.updateName(templateId, name);

    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    auditFacade.record(AuditAction.TEMPLATE_UPDATE, AuditTargetType.TEMPLATE, templateId, detail);
    log.info("改模板名 id={}：{} → {}", templateId, current.getName(), name);
  }

  // ------------------------------------------------------------------ 维度

  @Transactional
  public EffectiveVersion addDimension(
      long versionId, String name, BigDecimal weight, int sortNo, String suggestedDept, boolean isKey) {
    EffectiveVersion effective = ensureEditable(versionId);
    requireText(name, 100, "维度名称");
    requireWeightInRange(weight);
    requireLength(suggestedDept, 50, "建议评估部门");

    Dimension dimension = new Dimension();
    dimension.setTemplateVersionId(effective.versionId());
    dimension.setName(name);
    dimension.setWeight(weight);
    dimension.setSortNo(sortNo);
    dimension.setSuggestedDept(suggestedDept);
    dimension.setIsKey(isKey);
    dimension.setVersion(0);
    dimensionMapper.insert(dimension);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("name", name);
    detail.put("weight", weight);
    auditFacade.record(
        AuditAction.DIMENSION_CREATE, AuditTargetType.TEMPLATE, dimension.getId(), detail);
    log.info("新增维度 {}（id={}）到版本 {}", name, dimension.getId(), effective.versionId());
    return effective;
  }

  @Transactional
  public EffectiveVersion updateDimension(
      long dimensionId,
      String name,
      BigDecimal weight,
      int sortNo,
      String suggestedDept,
      boolean isKey,
      int version) {
    Dimension current = requireDimension(dimensionId);
    EffectiveVersion effective = ensureEditable(current.getTemplateVersionId());
    requireText(name, 100, "维度名称");
    requireWeightInRange(weight);
    requireLength(suggestedDept, 50, "建议评估部门");

    // 落到新草稿时，手里这一行已经是"旧版本的旧行"：用映射找到新草稿里的对应行。
    // 复用别人的草稿时映射为空——那一行确实不在生效版本里，报不存在让用户刷新后重做，而不是猜。
    long targetId = effective.requireDimensionId(dimensionId, current.getTemplateVersionId());
    // 复制出来的行是全新的、version 必为 0，不能拿调用方手里那个（属于旧行的）版本号去守卫。
    int guardVersion = effective.copied() ? 0 : version;

    // 审计记**决定计分口径的每一个字段**，不只是 name/weight：suggestedDept 是 P3 发布任务时自动指派的
    // 依据（01 J2）、isKey 供预警规则用（01 W1②）。少记它们，审计答不了"这份发布的指派/预警口径为什么变了"
    // （03 §26.5 的 P1-6）。用 putIfChanged 保证只记**真正变化**的字段。
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "weight", current.getWeight(), weight);
    AuditDetails.putIfChanged(detail, "sortNo", current.getSortNo(), sortNo);
    AuditDetails.putIfChanged(detail, "suggestedDept", current.getSuggestedDept(), suggestedDept);
    AuditDetails.putIfChanged(detail, "isKey", current.getIsKey(), isKey);
    if (dimensionMapper.update(targetId, name, weight, sortNo, suggestedDept, isKey, guardVersion) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(AuditAction.DIMENSION_UPDATE, AuditTargetType.TEMPLATE, targetId, detail);
    log.info("改维度 id={}（生效版本 {}）", targetId, effective.versionId());
    return effective;
  }

  /**
   * 删维度：**同事务连带删掉它的全部指标**（12 §E13）。
   *
   * <p>同一个事务是硬要求：表上没有外键（06 §4 不要求），漏删就会留下孤儿指标——整树读取看不见它们，
   * 但自动复制会按 `template_version_id` 把它们一起复制过去，而发布校验只查"某维度无指标"，
   * 孤儿指标永远不会让校验失败。
   */
  @Transactional
  public EffectiveVersion deleteDimension(long dimensionId, int version) {
    Dimension current = requireDimension(dimensionId);
    EffectiveVersion effective = ensureEditable(current.getTemplateVersionId());
    long targetId = effective.requireDimensionId(dimensionId, current.getTemplateVersionId());
    int guardVersion = effective.copied() ? 0 : version;

    if (dimensionMapper.delete(targetId, guardVersion) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    indicatorMapper.deleteByDimensionId(targetId);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("name", current.getName());
    detail.put("weight", current.getWeight());
    auditFacade.record(AuditAction.DIMENSION_DELETE, AuditTargetType.TEMPLATE, targetId, detail);
    log.info("删维度 id={}（连带其指标，生效版本 {}）", targetId, effective.versionId());
    return effective;
  }

  // ------------------------------------------------------------------ 指标

  @Transactional
  public EffectiveVersion addIndicator(
      long dimensionId,
      String name,
      String code,
      BigDecimal weight,
      IndicatorType type,
      ScoreMode scoreMode,
      String unit,
      BigDecimal targetValue,
      String standard,
      int sortNo) {
    Dimension dimension = requireDimension(dimensionId);
    EffectiveVersion effective = ensureEditable(dimension.getTemplateVersionId());
    requireText(name, 100, "指标名称");
    requireText(code, 50, "指标编码");
    requireWeightInRange(weight);
    requireLength(unit, 20, "单位");
    requireLength(standard, 500, "评分标准");
    // 关键：落到新草稿时 dimension.getId() 属于**旧版本**，必须翻译成新草稿里的行 id。
    // 漏了这一步不会报错（表上无外键），但会写出一行"版本指向新草稿、维度指向旧版本"的脏指标：
    // 树上按 dimension_id 分组看不见它、发布校验按 dimension_id 联结不计它的权重、删维度也删不掉它（04-057）。
    long targetDimensionId =
        effective.requireDimensionId(dimensionId, dimension.getTemplateVersionId());

    requireCodeAvailable(effective.versionId(), code, null);

    Indicator indicator = new Indicator();
    indicator.setDimensionId(targetDimensionId);
    indicator.setTemplateVersionId(effective.versionId());
    indicator.setName(name);
    indicator.setCode(code);
    indicator.setWeight(weight);
    indicator.setType(type);
    indicator.setScoreMode(scoreMode);
    indicator.setUnit(unit);
    indicator.setTargetValue(targetValue);
    indicator.setStandard(standard);
    indicator.setSortNo(sortNo);
    indicator.setVersion(0);
    insertIndicatorOrConflict(indicator);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", code);
    detail.put("name", name);
    auditFacade.record(
        AuditAction.INDICATOR_CREATE, AuditTargetType.TEMPLATE, indicator.getId(), detail);
    log.info("新增指标 {}（id={}）到版本 {}", code, indicator.getId(), effective.versionId());
    return effective;
  }

  @Transactional
  public EffectiveVersion updateIndicator(
      long indicatorId,
      String name,
      String code,
      BigDecimal weight,
      IndicatorType type,
      ScoreMode scoreMode,
      String unit,
      BigDecimal targetValue,
      String standard,
      int sortNo,
      int version) {
    Indicator current = requireIndicator(indicatorId);
    EffectiveVersion effective = ensureEditable(current.getTemplateVersionId());
    requireText(name, 100, "指标名称");
    requireText(code, 50, "指标编码");
    requireWeightInRange(weight);
    requireLength(unit, 20, "单位");
    requireLength(standard, 500, "评分标准");
    long targetId = effective.requireIndicatorId(indicatorId, current.getTemplateVersionId());
    int guardVersion = effective.copied() ? 0 : version;

    requireCodeAvailable(effective.versionId(), code, targetId);

    // 审计记**决定计分口径的每一个字段**（03 §26.5 的 P1-6）：type/scoreMode 决定走哪条计分策略、
    // targetValue 是 TARGET 换算的分母、standard 是 DIRECT 的人工换算表（12 §E15）——只记 code/name/weight
    // 的话，审计答不了"这份发布的算法为什么变了"，而 01 SM5 要求模板变更留痕的动机正在于此。
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), code);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "weight", current.getWeight(), weight);
    AuditDetails.putIfChanged(detail, "type", current.getType(), type);
    AuditDetails.putIfChanged(detail, "scoreMode", current.getScoreMode(), scoreMode);
    AuditDetails.putIfChanged(detail, "unit", current.getUnit(), unit);
    AuditDetails.putIfChanged(detail, "targetValue", current.getTargetValue(), targetValue);
    AuditDetails.putIfChanged(detail, "standard", current.getStandard(), standard);
    AuditDetails.putIfChanged(detail, "sortNo", current.getSortNo(), sortNo);
    // 唯一键也会被 UPDATE 踩到（不只是 INSERT）：把 A 的编码改成 B 已在用的值时，前置查重在并发下可能读的是
    // 旧快照（04-057 的同一根因：读的结果决定后续动作，而那个读不是当前读），真正的判定落在这条 UPDATE 上——
    // 不翻译就会漏成 5001（04-030 的形态：同一约束只在一部分写路径上处理）。
    try {
      if (indicatorMapper.update(
              targetId, name, code, weight, type, scoreMode, unit, targetValue, standard, sortNo, guardVersion)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + code);
    } catch (PessimisticLockingFailureException ex) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    auditFacade.record(AuditAction.INDICATOR_UPDATE, AuditTargetType.TEMPLATE, targetId, detail);
    log.info("改指标 id={}（生效版本 {}），改动 {}", targetId, effective.versionId(), detail.keySet());
    return effective;
  }

  @Transactional
  public EffectiveVersion deleteIndicator(long indicatorId, int version) {
    Indicator current = requireIndicator(indicatorId);
    EffectiveVersion effective = ensureEditable(current.getTemplateVersionId());
    long targetId = effective.requireIndicatorId(indicatorId, current.getTemplateVersionId());
    int guardVersion = effective.copied() ? 0 : version;

    if (indicatorMapper.delete(targetId, guardVersion) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", current.getCode());
    auditFacade.record(AuditAction.INDICATOR_DELETE, AuditTargetType.TEMPLATE, targetId, detail);
    log.info("删指标 id={}（生效版本 {}）", targetId, effective.versionId());
    return effective;
  }

  // ------------------------------------------------------------------ 发布

  /**
   * 发布（01 T1/T4、01 §6.6、12 §E4/E6/E12）：五类校验各自给出可定位的 message，最后用守卫 UPDATE 落状态。
   *
   * <p>顺序有意如此：先把"内容能不能发"判完，最后才动状态——这样校验失败时一行都没改（也就不需要回滚
   * 任何东西），而状态竞争由 UPDATE 的 `status = 'DRAFT' AND version = ?` 兜住。
   *
   * <p>**草稿允许权重合计不为 100**（E4）：否则用户想把 40 改成 30 时会先被拦住、根本改不动。校验只在这里。
   *
   * <p>⚠️ <b>第一条语句必须是锁版本行的当前读</b>（`findByIdForUpdate`），这是 04-057 修的那个 P0：
   * 校验的**读集**与落状态的**写**之间必须有互斥。另一个事务完全可能"改了权重、还没提交"——此时普通读看到
   * 的仍是旧的合规值、五类校验全过，而它提交之后本事务的守卫 UPDATE 照样成立（维度编辑只 bump
   * `dimension.version`，不动版本行的 `version`），于是一份**从未通过校验的内容**被发布出去（违反 01 T4
   * 与 05 ADR-3 的本意）。锁定读与「编辑」的 `FOR UPDATE` 互斥，校验读集才可信。
   *
   * <p>⚠️ 写成**第一条**也不是风格问题：REPEATABLE READ 下 read view 由事务的**第一条一致性读**建立，
   * 锁定读不建立它；若先做一次普通读（如 `requireVersion`）再锁，快照已被钉死，锁了也白锁。
   * `TemplateConcurrencyIntegrationTest` 有一条用例专门钉这个事实。
   */
  @Transactional
  public void publish(long versionId, int version) {
    TemplateVersion current =
        templateVersionMapper
            .findByIdForUpdate(versionId)
            .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_VERSION_NOT_FOUND));

    List<DimensionValidationRow> rows = dimensionMapper.findForValidation(versionId);
    if (rows.isEmpty()) {
      throw new BizException(ErrorCode.TEMPLATE_DIMENSION_EMPTY, "模板至少需要一个维度");
    }

    BigDecimal dimensionSum =
        rows.stream()
            .map(DimensionValidationRow::getDimensionWeight)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (dimensionSum.compareTo(FULL_WEIGHT) != 0) {
      throw new BizException(
          ErrorCode.TEMPLATE_WEIGHT_INVALID, "维度权重合计为 " + dimensionSum + "，应为 100");
    }

    for (DimensionValidationRow row : rows) {
      if (row.getIndicatorCount() == null || row.getIndicatorCount() == 0) {
        throw new BizException(
            ErrorCode.TEMPLATE_DIMENSION_EMPTY, "维度【" + row.getName() + "】至少需要一个指标");
      }
      if (row.getIndicatorWeightSum().compareTo(FULL_WEIGHT) != 0) {
        throw new BizException(
            ErrorCode.TEMPLATE_WEIGHT_INVALID,
            "维度【" + row.getName() + "】内指标权重合计为 " + row.getIndicatorWeightSum() + "，应为 100");
      }
    }

    List<String> badTargets = indicatorMapper.findInvalidTargets(versionId);
    if (!badTargets.isEmpty()) {
      throw new BizException(
          ErrorCode.TEMPLATE_TARGET_INVALID, "以下 TARGET 指标缺目标值或目标值 ≤ 0：" + badTargets);
    }

    // 正常情况下唯一键已保证版本内编码唯一，这条是防御（12 §E11 要求"发布时再兜一次"）
    List<String> duplicates = indicatorMapper.findDuplicateCodes(versionId);
    if (!duplicates.isEmpty()) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "同一版本内编码重复：" + duplicates);
    }

    if (templateVersionMapper.publish(versionId, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("versionNo", current.getVersionNo());
    detail.put("dimensions", rows.size());
    auditFacade.record(
        AuditAction.TEMPLATE_VERSION_PUBLISH, AuditTargetType.TEMPLATE, versionId, detail);
    log.info("发布模板版本 id={}（版本号 {}，维度 {} 个）", versionId, current.getVersionNo(), rows.size());
  }

  // ------------------------------------------------------------------ 确保可编辑

  /**
   * 保证写操作落在一个**草稿**上（12 §E5 / §E12）：本来就是草稿就原样返回；是已发布版本就先复制出新草稿。
   *
   * <p>三步，缺一不可：
   * <ol>
   *   <li><b>锁模板行</b>：`SELECT ... FOR UPDATE` 把同一模板下的复制串行化。只靠"先查 max 再插"不够——
   *       `SELECT MAX()` 在 REPEATABLE READ 下是一致性读，两个请求可能各读到同一个值、各插一份草稿，
   *       用户的改动被分到两个版本里，谁都不报错（唯一键只能防"同一版本号插两次"）；</li>
   *   <li><b>复用同源草稿</b>：已经有人为这个已发布版本建过草稿就直接用它，不再复制第二份；</li>
   *   <li><b>复制整棵树</b>：把维度与指标一并复制，并记下**旧行 id → 新行 id** 的映射。</li>
   * </ol>
   */
  @Transactional
  public EffectiveVersion ensureEditable(long versionId) {
    // 当前读（不是 requireVersion 的普通读）：状态决定"要不要复制"，而普通读走的是事务开始时的快照——
    // 另一个事务可能刚把这个草稿发布了，拿快照里的 DRAFT 去插入维度就会污染已发布版本，且没有守卫能拦
    // （版本守卫挡的是 UPDATE，不是 INSERT）。FOR UPDATE 同时与「发布」那条 UPDATE 互斥，读到的状态才是权威的。
    TemplateVersion version =
        templateVersionMapper
            .findByIdForUpdate(versionId)
            .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_VERSION_NOT_FOUND));

    if (version.getStatus() == TemplateVersionStatus.DRAFT) {
      return EffectiveVersion.sameAs(version.getId(), version.getVersion());
    }

    // 锁顺序在所有路径上一致：先版本行、再模板行（模板行锁用来串行化"同一模板下的复制"）
    long templateId = version.getTemplateId();
    templateMapper.lockById(templateId)
        .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND));

    return templateVersionMapper
        .findDraftCopiedFrom(templateId, version.getId())
        .map(
            draft ->
                // 复用它：本次没有发生复制，因此不写 TEMPLATE_VERSION_AUTO_COPY，
                // 也不带 id 映射——调用方若想"改/删某一行"，那一行不在这个版本里（EffectiveVersion 有判据）
                new EffectiveVersion(draft.getId(), draft.getVersion(), false, Map.of(), Map.of()))
        .orElseGet(() -> copyToNewDraft(version, templateId));
  }

  /** 把源版本整棵树复制成新草稿，返回新版本信息 + 旧行到新行的 id 映射。 */
  private EffectiveVersion copyToNewDraft(TemplateVersion source, long templateId) {
    TemplateVersion draft = new TemplateVersion();
    draft.setTemplateId(templateId);
    draft.setVersionNo(templateVersionMapper.findMaxVersionNoForUpdate(templateId).orElse(0) + 1);
    draft.setStatus(TemplateVersionStatus.DRAFT);
    draft.setCopiedFromVersionId(source.getId());
    draft.setVersion(0);
    insertVersionOrConflict(draft);

    Map<Long, Long> dimensionIdMap = new HashMap<>();
    Map<Long, Long> indicatorIdMap = new HashMap<>();

    List<Dimension> dimensions = dimensionMapper.findByVersionId(source.getId());
    Map<Long, List<Indicator>> byDimension =
        indicatorMapper.findByVersionId(source.getId()).stream()
            .collect(Collectors.groupingBy(Indicator::getDimensionId));

    for (Dimension dimension : dimensions) {
      Dimension copy = new Dimension();
      copy.setTemplateVersionId(draft.getId());
      copy.setName(dimension.getName());
      copy.setWeight(dimension.getWeight());
      copy.setSortNo(dimension.getSortNo());
      copy.setSuggestedDept(dimension.getSuggestedDept());
      copy.setIsKey(Boolean.TRUE.equals(dimension.getIsKey()));
      copy.setVersion(0);
      dimensionMapper.insert(copy);
      dimensionIdMap.put(dimension.getId(), copy.getId());

      for (Indicator indicator : byDimension.getOrDefault(dimension.getId(), List.of())) {
        Indicator indicatorCopy = new Indicator();
        indicatorCopy.setDimensionId(copy.getId());
        indicatorCopy.setTemplateVersionId(draft.getId());
        indicatorCopy.setName(indicator.getName());
        indicatorCopy.setCode(indicator.getCode());
        indicatorCopy.setWeight(indicator.getWeight());
        indicatorCopy.setType(indicator.getType());
        indicatorCopy.setScoreMode(indicator.getScoreMode());
        indicatorCopy.setUnit(indicator.getUnit());
        indicatorCopy.setTargetValue(indicator.getTargetValue());
        indicatorCopy.setStandard(indicator.getStandard());
        indicatorCopy.setSortNo(indicator.getSortNo());
        indicatorCopy.setVersion(0);
        insertIndicatorOrConflict(indicatorCopy);
        indicatorIdMap.put(indicator.getId(), indicatorCopy.getId());
      }
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("fromVersionId", source.getId());
    detail.put("fromVersionNo", source.getVersionNo());
    auditFacade.record(
        AuditAction.TEMPLATE_VERSION_AUTO_COPY,
        AuditTargetType.TEMPLATE,
        draft.getId(),
        detail);
    log.info(
        "已发布版本 id={} 被修改，自动复制出草稿 id={}（维度 {} 个、指标 {} 个）",
        source.getId(),
        draft.getId(),
        dimensionIdMap.size(),
        indicatorIdMap.size());

    return new EffectiveVersion(draft.getId(), draft.getVersion(), true, dimensionIdMap, indicatorIdMap);
  }

  // ------------------------------------------------------------------ 辅助

  private TemplateVersion requireVersion(long versionId) {
    return templateVersionMapper
        .findById(versionId)
        .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_VERSION_NOT_FOUND));
  }

  private Dimension requireDimension(long dimensionId) {
    return dimensionMapper
        .findById(dimensionId)
        .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND));
  }

  private Indicator requireIndicator(long indicatorId) {
    return indicatorMapper
        .findById(indicatorId)
        .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND));
  }

  /**
   * 权重的单行校验：范围 `0~100` + 最多两位小数。
   *
   * <p>与 `V7` 的 `ck_dimension_weight` / `ck_indicator_weight` 一致，精度与 `DECIMAL(5,2)` 一致。
   * **两层校验不是冗余**：`@Valid` 只覆盖 HTTP 入口，而 P3 的 Excel 批量导入与 M6 的开放 API 都要写
   * 这些字段、都不过 DTO。漏了这一层，越界值会一路走到 CHECK 才被库拒绝，经兜底变成 5001，用户看到的
   * 是"系统异常"而不是"哪一项不对"（06 §2.6 的红线、03 §26.5 的 P1-5）。
   *
   * <p>精度用 `stripTrailingZeros().scale()`：`33.340` 的 scale 是 3，但它等于 `33.34`，不该被拒。
   */
  private static void requireWeightInRange(BigDecimal weight) {
    if (weight == null
        || weight.compareTo(BigDecimal.ZERO) < 0
        || weight.compareTo(FULL_WEIGHT) > 0) {
      throw new BizException(ErrorCode.TEMPLATE_VALUE_INVALID, "权重须在 0~100 之间，实际为 " + weight);
    }
    if (weight.stripTrailingZeros().scale() > 2) {
      throw new BizException(ErrorCode.TEMPLATE_VALUE_INVALID, "权重最多保留两位小数，实际为 " + weight);
    }
  }

  /** 必填文本字段：非空且不超长。长度上限与 `V7` 的列定义一一对应。 */
  private static void requireText(String value, int max, String field) {
    if (value == null || value.isBlank()) {
      throw new BizException(ErrorCode.TEMPLATE_VALUE_INVALID, field + "不能为空");
    }
    requireLength(value, max, field);
  }

  /** 可空文本字段：`null` 表示"不设置"，不算错误；超长才拦。 */
  private static void requireLength(String value, int max, String field) {
    if (value != null && value.length() > max) {
      throw new BizException(
          ErrorCode.TEMPLATE_VALUE_INVALID, field + "长度不得超过 " + max + "，实际为 " + value.length());
    }
  }

  /** 编码查重（12 §E11）：版本内唯一，排除自己那条；保存时就拦，别等发布。 */
  private void requireCodeAvailable(long versionId, String code, Long excludeId) {
    if (indicatorMapper.countByCode(versionId, code, excludeId) > 0) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + code);
    }
  }

  /**
   * 插入版本并把并发异常翻成 3001（见 {@link #create} 的说明）。
   *
   * <p>**两类都要翻**（12 §6 风险表第 2 行与 §E12 的明文要求）：唯一键冲突（`DuplicateKeyException`）与
   * 锁等待/死锁（`PessimisticLockingFailureException`，含 `CannotAcquireLockException`——REPEATABLE READ 下
   * 唯一索引插入会先等锁、超时抛它）。只翻前者的话，后者会经兜底变成 5001。
   */
  private void insertVersionOrConflict(TemplateVersion version) {
    try {
      templateVersionMapper.insert(version);
    } catch (DuplicateKeyException | PessimisticLockingFailureException ex) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
  }

  /** 插入指标并把两类并发异常翻成 2014（与前置查重同一个码，别让并发那条路径漏成 5xxx）。 */
  private void insertIndicatorOrConflict(Indicator indicator) {
    try {
      indicatorMapper.insert(indicator);
    } catch (DuplicateKeyException | PessimisticLockingFailureException ex) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + indicator.getCode());
    }
  }
}
