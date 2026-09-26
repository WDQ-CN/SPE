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

    // 落到新草稿时，手里这一行已经是"旧版本的旧行"：用映射找到新草稿里的对应行。
    // 复用别人的草稿时映射为空——那一行确实不在生效版本里，报不存在让用户刷新后重做，而不是猜。
    if (effective.switchedWithoutMap(current.getTemplateVersionId())) {
      throw new BizException(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND);
    }
    long targetId = effective.resolveDimension(dimensionId);
    // 复制出来的行是全新的、version 必为 0，不能拿调用方手里那个（属于旧行的）版本号去守卫。
    int guardVersion = effective.copied() ? 0 : version;

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("before", describe(current));
    if (dimensionMapper.update(targetId, name, weight, sortNo, suggestedDept, isKey, guardVersion) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Dimension after = new Dimension();
    after.setName(name);
    after.setWeight(weight);
    detail.put("after", describe(after));
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
    if (effective.switchedWithoutMap(current.getTemplateVersionId())) {
      throw new BizException(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND);
    }
    long targetId = effective.resolveDimension(dimensionId);
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

    requireCodeAvailable(effective.versionId(), code, null);

    Indicator indicator = new Indicator();
    indicator.setDimensionId(dimension.getId());
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
    insertIndicatorOrConflict(indicator, effective.versionId());

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
    if (effective.switchedWithoutMap(current.getTemplateVersionId())) {
      throw new BizException(ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND);
    }
    long targetId = effective.resolveIndicator(indicatorId);
    int guardVersion = effective.copied() ? 0 : version;

    requireCodeAvailable(effective.versionId(), code, targetId);

    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), code);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "weight", current.getWeight(), weight);
    if (indicatorMapper.update(
            targetId, name, code, weight, type, scoreMode, unit, targetValue, standard, sortNo, guardVersion)
        == 0) {
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
    if (effective.switchedWithoutMap(current.getTemplateVersionId())) {
      throw new BizException(ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND);
    }
    long targetId = effective.resolveIndicator(indicatorId);
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
   */
  @Transactional
  public void publish(long versionId, int version) {
    TemplateVersion current = requireVersion(versionId);

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
    TemplateVersion version = requireVersion(versionId);
    if (version.getStatus() == TemplateVersionStatus.DRAFT) {
      return EffectiveVersion.sameAs(version.getId(), version.getVersion());
    }

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
        insertIndicatorOrConflict(indicatorCopy, draft.getId());
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

  /** 编码查重（12 §E11）：版本内唯一，排除自己那条；保存时就拦，别等发布。 */
  private void requireCodeAvailable(long versionId, String code, Long excludeId) {
    if (indicatorMapper.countByCode(versionId, code, excludeId) > 0) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + code);
    }
  }

  /** 插入版本并把唯一键冲突翻成 3001（见 {@link #create} 的说明）。 */
  private void insertVersionOrConflict(TemplateVersion version) {
    try {
      templateVersionMapper.insert(version);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
  }

  /** 插入指标并把唯一键冲突翻成 2014（与前置查重同一个码，别让并发那条路径漏成 5xxx）。 */
  private void insertIndicatorOrConflict(Indicator indicator, long versionId) {
    try {
      indicatorMapper.insert(indicator);
    } catch (DuplicateKeyException ex) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + indicator.getCode());
    }
  }

  private static String describe(Dimension dimension) {
    return "name=" + dimension.getName() + ", weight=" + dimension.getWeight();
  }
}
