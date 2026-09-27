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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // 落到新草稿时，手里这一行已经是"旧版本的旧行"：用映射找到**生效版本**里的对应行，
    // 并连同它**当前的 version** 一起拿回来——守卫必须用目标行的版本，不是调用方手里源行的
    // （04-074 的 P1：复用别人的草稿时草稿那一行可能已被改过，用源行的版本号会误报 3001）。
    // 复用别人的草稿且配不上时抛 2015：那一行确实不在生效版本里，让用户刷新后重做，而不是猜。
    EffectiveVersion.Target target =
        effective.requireDimensionRow(dimensionId, current.getTemplateVersionId(), version);

    // 审计记**决定计分口径的每一个字段**，不只是 name/weight：suggestedDept 是 P3 发布任务时自动指派的
    // 依据（01 J2）、isKey 供预警规则用（01 W1②）。少记它们，审计答不了"这份发布的指派/预警口径为什么变了"
    // （03 §26.5 的 P1-6）。用 putIfChanged 保证只记**真正变化**的字段。
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "weight", current.getWeight(), weight);
    AuditDetails.putIfChanged(detail, "sortNo", current.getSortNo(), sortNo);
    AuditDetails.putIfChanged(detail, "suggestedDept", current.getSuggestedDept(), suggestedDept);
    AuditDetails.putIfChanged(detail, "isKey", current.getIsKey(), isKey);
    int affected;
    try {
      affected =
          dimensionMapper.update(
              target.id(),
              name,
              weight,
              sortNo,
              suggestedDept,
              isKey,
              target.version(),
              effective.versionId());
    } catch (PessimisticLockingFailureException ex) {
      // 锁等待/死锁与"守卫 UPDATE 影响 0 行"是同一类冲突 ⇒ 同一个码（04-079：原先这条路径没翻）
      throw lockConflict();
    }
    if (affected == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(AuditAction.DIMENSION_UPDATE, AuditTargetType.TEMPLATE, target.id(), detail);
    log.info("改维度 id={}（生效版本 {}）", target.id(), effective.versionId());
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
    EffectiveVersion.Target target =
        effective.requireDimensionRow(dimensionId, current.getTemplateVersionId(), version);

    int affected;
    try {
      affected = dimensionMapper.delete(target.id(), target.version(), effective.versionId());
    } catch (PessimisticLockingFailureException ex) {
      throw lockConflict();
    }
    if (affected == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    indicatorMapper.deleteByDimensionId(target.id());

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("name", current.getName());
    detail.put("weight", current.getWeight());
    auditFacade.record(AuditAction.DIMENSION_DELETE, AuditTargetType.TEMPLATE, target.id(), detail);
    log.info("删维度 id={}（连带其指标，生效版本 {}）", target.id(), effective.versionId());
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
    requireTypeScoreMode(type, scoreMode);
    requireTargetValue(targetValue);
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
    requireTypeScoreMode(type, scoreMode);
    requireTargetValue(targetValue);
    requireLength(unit, 20, "单位");
    requireLength(standard, 500, "评分标准");
    EffectiveVersion.Target target =
        effective.requireIndicatorRow(indicatorId, current.getTemplateVersionId(), version);

    requireCodeAvailable(effective.versionId(), code, target.id());

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
              target.id(),
              name,
              code,
              weight,
              type,
              scoreMode,
              unit,
              targetValue,
              standard,
              sortNo,
              target.version(),
              effective.versionId())
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + code);
    } catch (PessimisticLockingFailureException ex) {
      throw lockConflict();
    }

    auditFacade.record(AuditAction.INDICATOR_UPDATE, AuditTargetType.TEMPLATE, target.id(), detail);
    log.info(
        "改指标 id={}（生效版本 {}），改动 {}",
        target.id(),
        effective.versionId(),
        detail.keySet());
    return effective;
  }

  @Transactional
  public EffectiveVersion deleteIndicator(long indicatorId, int version) {
    Indicator current = requireIndicator(indicatorId);
    EffectiveVersion effective = ensureEditable(current.getTemplateVersionId());
    EffectiveVersion.Target target =
        effective.requireIndicatorRow(indicatorId, current.getTemplateVersionId(), version);

    int affected;
    try {
      affected = indicatorMapper.delete(target.id(), target.version(), effective.versionId());
    } catch (PessimisticLockingFailureException ex) {
      throw lockConflict();
    }
    if (affected == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", current.getCode());
    auditFacade.record(AuditAction.INDICATOR_DELETE, AuditTargetType.TEMPLATE, target.id(), detail);
    log.info("删指标 id={}（生效版本 {}）", target.id(), effective.versionId());
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
        .map(draft -> reuseExistingDraft(draft, version))
        .orElseGet(() -> copyToNewDraft(version, templateId));
  }

  /**
   * 复用别人已经建好的同源草稿，并**重建「源行 id → 草稿行 id」的映射**（12 §E5 的并发意图）。
   *
   * <p>⚠️ 这里原先返回空映射，于是调用方拿到 2015「维度不存在」——**而那一行明明在**（复制时照抄过去了），
   * 只是没有映射能定位它。后果是 E5 明写的场景「两个人同时改同一已发布版本的**不同维度**，理应落在
   * **同一份**新草稿上」实际会失败，而且报的错有误导性（说"不存在"，其实存在）。P2 深审用一个并发用例把它
   * 钉住了（`TemplateConcurrencyIntegrationTest.concurrentEditsOnPublishedVersionShareOneDraft`）。
   *
   * <p>重建方式：**指标按 `code`**（版本内有唯一键，最可靠）、**维度按 `(sort_no, name)`**（维度没有唯一键，
   * 但复制是逐一照抄，这两个字段必然原样带过去）。配对不上的行**不进映射**，调用方拿到 2015/2016——
   * 那时它确实不在草稿里（例如它在源版本存在、但配对键被改过，或那一行被别人删掉了）。
   *
   * <p>⚠️ **配对键重复时一律不进映射**（03 §31.7.14 的 P2-11）：`(sort_no, name)` 在库里**没有唯一键**，
   * 同一版本里可以存在两行同序号同名。按 key 建 map 时**后者会覆盖前者**，于是"改 A 却落到 B"——
   * **静默改错行**，比报错严重得多。因此某键在本侧出现不止一次就标记为**歧义**、不进映射 ⇒ 调用方拿到 2015
   * （响亮），而不是被配到"另一行"。（指标侧不需要这层：`uk_indicator_version_code` 已保证版本内编码唯一。）
   *
   * <p>`copied` 保持 `false`：本次**没有**发生复制，不该写 `TEMPLATE_VERSION_AUTO_COPY` 审计
   * （12 §E5 的要求）。能否定位行由映射本身决定，与这个标志解耦。
   * <p>⚠️ 这里的四条查询**必须用当前读**（`...ForShare`）。第一版我用了普通读，结果用例仍然红了：
   * 调用方（如 `updateDimension`）在进入本方法前先做了一次 `requireDimension` 普通读，**read view 就此钉死**；
   * 之后即便本方法拿了锁，普通读看到的仍是对方**提交前**的快照 → 草稿的维度一条都查不到 → 配对全空。
   * 这是 CLAUDE 第 6 条那个坑的第四次显形，而且比前几次更隐蔽：**旧读发生在锁之后**。
   */
  private EffectiveVersion reuseExistingDraft(TemplateVersion draft, TemplateVersion source) {
    Map<String, Dimension> draftDimensions = new HashMap<>();
    Set<String> ambiguousDimensionKeys = new HashSet<>();
    for (Dimension dimension : dimensionMapper.findByVersionIdForShare(draft.getId())) {
      String key = dimensionKey(dimension.getSortNo(), dimension.getName());
      if (draftDimensions.put(key, dimension) != null) {
        ambiguousDimensionKeys.add(key);
      }
    }
    Map<Long, EffectiveVersion.Target> dimensionTargets = new HashMap<>();
    for (Dimension dimension : dimensionMapper.findByVersionIdForShare(source.getId())) {
      String key = dimensionKey(dimension.getSortNo(), dimension.getName());
      if (ambiguousDimensionKeys.contains(key)) {
        continue; // 歧义键：宁可不映射（调用方拿到 2015），也不把它配到"另一行"
      }
      Dimension copy = draftDimensions.get(key);
      if (copy != null) {
        // ⚠️ 守卫用**草稿这一行当前**的 version，不是调用方手里源行的——拿源行的版本号守卫会让
        // "没人动过的行"被误报 3001（实测过），反方向还可能放过一次 stale 写（04-074 的 P1）。
        dimensionTargets.put(
            dimension.getId(), new EffectiveVersion.Target(copy.getId(), copy.getVersion()));
      }
    }

    Map<String, Indicator> draftIndicators = new HashMap<>();
    for (Indicator indicator : indicatorMapper.findByVersionIdForShare(draft.getId())) {
      draftIndicators.put(indicator.getCode(), indicator);
    }
    Map<Long, EffectiveVersion.Target> indicatorTargets = new HashMap<>();
    for (Indicator indicator : indicatorMapper.findByVersionIdForShare(source.getId())) {
      Indicator copy = draftIndicators.get(indicator.getCode());
      if (copy != null) {
        indicatorTargets.put(
            indicator.getId(), new EffectiveVersion.Target(copy.getId(), copy.getVersion()));
      }
    }

    return new EffectiveVersion(
        draft.getId(), draft.getVersion(), false, dimensionTargets, indicatorTargets);
  }

  /** 维度的配对键：`sort_no` 可重复、名称也可重复，但两者的组合在"复制照抄"的语境下足够定位。 */
  private static String dimensionKey(Integer sortNo, String name) {
    return (sortNo == null ? 0 : sortNo) + "\u0000" + name;
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

    Map<Long, EffectiveVersion.Target> dimensionTargets = new HashMap<>();
    Map<Long, EffectiveVersion.Target> indicatorTargets = new HashMap<>();

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
      // 副本是全新行 ⇒ 它的当前 version 恒为 0（守卫用它，不是源行的版本号）
      dimensionTargets.put(dimension.getId(), new EffectiveVersion.Target(copy.getId(), 0));

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
        indicatorTargets.put(
            indicator.getId(), new EffectiveVersion.Target(indicatorCopy.getId(), 0));
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
        dimensionTargets.size(),
        indicatorTargets.size());

    return new EffectiveVersion(
        draft.getId(), draft.getVersion(), true, dimensionTargets, indicatorTargets);
  }

  // ------------------------------------------------------------------ 辅助

  private TemplateVersion requireVersion(long versionId) {
    return templateVersionMapper
        .findById(versionId)
        .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_VERSION_NOT_FOUND));
  }

  private Dimension requireDimension(long dimensionId) {
    return dimensionMapper
        .findByIdForUpdate(dimensionId)
        .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND));
  }

  private Indicator requireIndicator(long indicatorId) {
    return indicatorMapper
        .findByIdForUpdate(indicatorId)
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
   * <p>⚠️ 精度判据是 **`scale()`——不接受"多余但等值的尾零"**，与 DTO 的 `@Digits(fraction = 2)` **同口径**
   * （03 §31.7.4 的 P2）：服务层一度用 `stripTrailingZeros().scale()`，于是 `33.340` 在 DTO 被拒、在服务层
   * 被放行——**同一条约束两套口径**，程序化路径（P3 的 Excel 导入 / M6 的开放 API）收下的值，HTTP 路径
   * 根本收不下。现在两侧一致：`33.340` 一律拒（提示"最多两位小数"），`33.34` 一律收。
   */
  private static void requireWeightInRange(BigDecimal weight) {
    if (weight == null
        || weight.compareTo(BigDecimal.ZERO) < 0
        || weight.compareTo(FULL_WEIGHT) > 0) {
      throw new BizException(ErrorCode.TEMPLATE_VALUE_INVALID, "权重须在 0~100 之间，实际为 " + weight);
    }
    if (weight.scale() > 2) {
      throw new BizException(ErrorCode.TEMPLATE_VALUE_INVALID, "权重最多保留两位小数，实际为 " + weight);
    }
  }

  /**
   * 目标值的单行校验（`target_value DECIMAL(12,4)`）：最多 4 位小数 + 整数部分最多 8 位。
   *
   * <p>与 DTO 的 `@Digits(integer = 8, fraction = 4)` 同口径。**这一层原来完全缺失**（03 §31.7.14 的 P2-12），
   * 而 `DECIMAL(12,4)` 对**超过 4 位的小数会静默四舍五入**——实测写 `98.12345` 进库变成 `98.1235`，
   * 无错误、无警告，等于把 TARGET 换算的**分母**悄悄改掉。越界有 strict mode 兜底（`ERROR 1264`），
   * 精度没有兜底，所以必须在这里拦。`null` 合法：非 TARGET 指标不必填，TARGET 缺值由发布校验（2013）拦。
   */
  private static void requireTargetValue(BigDecimal targetValue) {
    if (targetValue == null) {
      return;
    }
    if (targetValue.scale() > 4) {
      throw new BizException(
          ErrorCode.TEMPLATE_VALUE_INVALID, "目标值最多保留四位小数，实际为 " + targetValue);
    }
    if (targetValue.precision() - targetValue.scale() > 8) {
      throw new BizException(
          ErrorCode.TEMPLATE_VALUE_INVALID, "目标值整数部分不得超过 8 位，实际为 " + targetValue);
    }
  }

  /**
   * `type` 与 `scoreMode` 的配套一致性（01 §6.1a；口径也写在 `V7` 的列注释里）。
   *
   * <p>**定量必须有计分模式**（DIRECT / RATIO / TARGET——P3 按它选策略），**定性必须没有**
   * （1~5 ×20 是固定换算，不走策略）。
   *
   * <p>⚠️ 这条校验原先**一层都没有**，而 `CreateIndicatorRequest` 的 javadoc 却写着"这一对约束的完整校验
   * 在服务层"——**注释在维护一条并不存在的保证**。后果：直连 API 传 `type=QUAL, scoreMode=RATIO` 能存进草稿、
   * 还能发布出去，而版本一经发布就**冻结、不可就地修**（要再发一版并重绑任务）；P3 的计分若按 `scoreMode`
   * 非空优先，定性指标就会被按定量口径算（03 §31.7.3 / §31.7.14 的 P2-13，两位审查者各自独立发现）。
   */
  private static void requireTypeScoreMode(IndicatorType type, ScoreMode scoreMode) {
    if (type == IndicatorType.QUANT && scoreMode == null) {
      throw new BizException(
          ErrorCode.TEMPLATE_VALUE_INVALID, "定量指标必须指定计分模式（DIRECT/RATIO/TARGET）");
    }
    if (type == IndicatorType.QUAL && scoreMode != null) {
      throw new BizException(
          ErrorCode.TEMPLATE_VALUE_INVALID, "定性指标的计分模式必须为空（固定按 1~5 ×20 换算，不走计分策略）");
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
   * **"锁冲突 → 3001"的单点定义**（12 §E12）：`PessimisticLockingFailureException` 涵盖锁等待超时与
   * InnoDB 死锁（`CannotAcquireLockException` 是它的子类——REPEATABLE READ 下唯一索引插入会先等锁、
   * 超时抛它）。
   *
   * <p>⚠️ **为什么要显式收成一处**（04-079）：这个翻译原先散在 3 个 `try/catch` 里，而
   * `updateDimension` / `deleteDimension` / `deleteIndicator` **一处都没有** —— 今天靠
   * `GlobalExceptionHandler` 的集中映射兜住了 HTTP 入口，但**服务层被内部调用**（P3 的 Excel 导入、
   * 事件监听、批处理——它们没有 HTTP 边界）时会漏成 **5xxx**。现在六条写路径全部显式翻译，
   * 且"翻成什么码"只在这里定义。
   */
  private static BizException lockConflict() {
    return new BizException(ErrorCode.CONCURRENT_CONFLICT);
  }

  /**
   * 插入版本并把并发异常翻成 3001（见 {@link #create} 的说明）。
   *
   * <p>**两类都要翻**（12 §6 风险表第 2 行与 §E12 的明文要求）：唯一键冲突（`DuplicateKeyException`，即
   * `uk_template_version_no`）与锁等待/死锁——两者都是"别人先动了一步"，对调用方是同一件事 ⇒ 同一个码。
   */
  private void insertVersionOrConflict(TemplateVersion version) {
    try {
      templateVersionMapper.insert(version);
    } catch (DuplicateKeyException | PessimisticLockingFailureException ex) {
      throw lockConflict();
    }
  }

  /**
   * 插入指标并把两类并发异常翻成 **2014**（与前置查重同一个码，别让并发那条路径漏成 5xxx）。
   *
   * <p>⚠️ 这里**有意**与 {@link #lockConflict} 不同：指标插入的唯一键是 `uk_indicator_version_code`，
   * 对用户来说"并发插入同编码"与"该版本内已存在编码"是同一件事 ⇒ 翻成 2014 更可行动（12 §E11）。
   * 别为了"统一"把它改成 3001。
   */
  private void insertIndicatorOrConflict(Indicator indicator) {
    try {
      indicatorMapper.insert(indicator);
    } catch (DuplicateKeyException | PessimisticLockingFailureException ex) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE, "该版本内已存在编码 " + indicator.getCode());
    }
  }
}
