package com.spe.context.evaluation.internal.task;

import com.spe.common.event.EventPublisher;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.api.SheetStatus;
import com.spe.context.evaluation.api.events.TaskPublishedEvent;
import com.spe.context.evaluation.internal.sheet.MySheetRow;
import com.spe.context.evaluation.internal.sheet.MySheetView;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import com.spe.context.system.api.UserStatus;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务域服务（01 J1~J4；05 §5.5 T1；13 §E10/E12/E13）。
 *
 * <p><b>⚠️ 草稿的"供应商勾选"存在哪里</b>（执行 W3.1 时发现的规格缺口）：`02 §3.1` 的 `eval_task`
 * 没有存供应商清单的列，而 01 J1 要求创建任务时就勾选、J4 又按"同一供应商同一周期只允许一个非取消任务"
 * 判定——所以勾选必须**落库**才有意义。三条路里选了第三条：
 * <ol>
 *   <li>改 V11 加列 —— **否决**：V11 已应用到 dev 库并记了 checksum，改它会让 Flyway 校验失败、后端起不来
 *       （04-077 的同一类事故）；要走也只能新开迁移，那是"为一个人工低频动作加列"。
 *   <li>发布接口带供应商清单 —— **否决**：草稿编辑页会丢失勾选，与 01 J1 的两步式创建不符。
 *   <li><b>草稿阶段就写 `task_supplier` 行</b>（本实现）：勾选即落库，发布时为这些行批量生成
 *       `SHEET`。代价是草稿期这些行的 status 先落 `SCORING`（没有"草稿"这个评估单状态），
 *       但草稿期的评估单不会被任何功能读到：看板只查 IN_PROGRESS 任务（J3）、待办只查 sheet（还没有）、
 *       提交要求 sheet 存在（W4）。**已回写 02 §3.1 与 13 §8**。
 * </ol>
 *
 * <p><b>事务形态</b>：三个写方法都是单一 `@Transactional`，多表写（任务 + 供应商行 + 审批链 + 评分单）
 * 与审计、事件都在同一事务里（05 §5.5 T1/T6、ADR-9）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

  /** 可被指派的角色（01 J2：指派对象仅限 EVALUATOR/MANAGER）。 */
  private static final List<Role> ASSIGNABLE_ROLES = List.of(Role.EVALUATOR, Role.MANAGER);

  private final EvalTaskMapper taskMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final ApprovalNodeMapper approvalNodeMapper;
  private final SheetMapper sheetMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final UserQueryFacade userQueryFacade;
  private final AuditFacade auditFacade;
  private final EventPublisher eventPublisher;

  /**
   * 任务列表（13 §E18 的 `GET /api/tasks`）：名称包含匹配 + 可选状态，按 id 倒序（新任务在前）。
   *
   * <p>分页入参走 `PageQuery`（单页上限单点定义），不自己算 offset（06 §4）。
   */
  @Transactional(readOnly = true)
  public com.spe.common.page.PageResult<TaskSummary> page(
      com.spe.common.page.PageQuery query, EvalTaskStatus status, String keyword) {
    String statusValue = status == null ? null : status.name();
    String like = keyword == null || keyword.isBlank() ? null : keyword.trim();
    long total = taskMapper.countByFilter(statusValue, like);
    List<TaskSummary> rows =
        taskMapper.findPage(statusValue, like, query.offset(), query.size()).stream()
            .map(TaskService::toSummary)
            .toList();
    return com.spe.common.page.PageResult.of(rows, total, query.page(), query.size());
  }

  /** 任务详情（13 §E18 的缺口端点）：任务 + 勾选供应商 + 审批链 + 两个评分单计数。 */
  @Transactional(readOnly = true)
  public TaskDetail findDetail(long taskId) {
    EvalTask task = taskMapper.findById(taskId).orElseThrow(TaskService::notFound);
    List<TaskSupplier> taskSuppliers = taskSupplierMapper.findByTaskId(taskId);
    Map<Long, SupplierBrief> suppliers = new LinkedHashMap<>();
    if (!taskSuppliers.isEmpty()) {
      supplierQueryFacade
          .findByIds(taskSuppliers.stream().map(TaskSupplier::getSupplierId).toList())
          .forEach(brief -> suppliers.put(brief.id(), brief));
    }
    List<ApprovalNode> nodes = approvalNodeMapper.findByTaskId(taskId);
    Map<Long, UserBrief> users = new LinkedHashMap<>();
    if (!nodes.isEmpty()) {
      userQueryFacade
          .findByIds(nodes.stream().map(ApprovalNode::getApproverId).toList())
          .forEach(brief -> users.put(brief.id(), brief));
    }
    List<TaskDetail.SupplierRef> supplierRefs =
        taskSuppliers.stream()
            .map(
                taskSupplier -> {
                  SupplierBrief brief = suppliers.get(taskSupplier.getSupplierId());
                  return new TaskDetail.SupplierRef(
                      taskSupplier.getSupplierId(),
                      brief == null ? null : brief.code(),
                      brief == null ? null : brief.name());
                })
            .toList();
    List<TaskDetail.ApproverRef> approverRefs =
        nodes.stream()
            .map(
                node -> {
                  UserBrief brief = users.get(node.getApproverId());
                  return new TaskDetail.ApproverRef(
                      node.getApproverId(), brief == null ? null : brief.name(), node.getSeq());
                })
            .toList();
    return new TaskDetail(
        task.getId(),
        task.getName(),
        task.getYear(),
        task.getQuarter(),
        task.getTemplateVersionId(),
        task.getDeadline(),
        task.getStatus(),
        task.getVersion(),
        supplierRefs,
        approverRefs,
        sheetMapper.countByTaskId(taskId),
        sheetMapper.countUnsubmittedByTaskId(taskId));
  }

  /**
   * 任务下的评分单清单（13 §E18 的补充端点）：详情页的「指派调整」要用它拿到 sheetId / 指派对象 / 版本。
   *
   * <p>四次批量查询（评分单、评估单映射、供应商名、指派对象名）+ 一次快照取维度名——**与评分单数无关**
   * （不是逐行回查），与看板同一条口径（E14）。
   */
  @Transactional(readOnly = true)
  public List<SheetBrief> findSheets(long taskId) {
    EvalTask task = taskMapper.findById(taskId).orElseThrow(TaskService::notFound);
    List<Sheet> sheets = sheetMapper.findByTaskId(taskId);
    if (sheets.isEmpty()) {
      return List.of();
    }
    Map<Long, Long> supplierIdByTaskSupplier = new LinkedHashMap<>();
    taskSupplierMapper
        .findByTaskId(taskId)
        .forEach(taskSupplier -> supplierIdByTaskSupplier.put(taskSupplier.getId(), taskSupplier.getSupplierId()));

    Map<Long, SupplierBrief> suppliers = new LinkedHashMap<>();
    supplierQueryFacade
        .findByIds(supplierIdByTaskSupplier.values().stream().distinct().toList())
        .forEach(supplier -> suppliers.put(supplier.id(), supplier));

    Map<Long, String> dimensionNames = new LinkedHashMap<>();
    templateSnapshotFacade
        .requirePublished(task.getTemplateVersionId())
        .dimensions()
        .forEach(dimension -> dimensionNames.put(dimension.id(), dimension.name()));

    Map<Long, UserBrief> assignees = new LinkedHashMap<>();
    List<Long> assigneeIds =
        sheets.stream().map(Sheet::getAssigneeId).filter(java.util.Objects::nonNull).distinct().toList();
    if (!assigneeIds.isEmpty()) {
      userQueryFacade.findByIds(assigneeIds).forEach(user -> assignees.put(user.id(), user));
    }

    List<SheetBrief> result = new ArrayList<>(sheets.size());
    for (Sheet sheet : sheets) {
      Long supplierId = supplierIdByTaskSupplier.get(sheet.getTaskSupplierId());
      SupplierBrief supplier = supplierId == null ? null : suppliers.get(supplierId);
      UserBrief assignee = sheet.getAssigneeId() == null ? null : assignees.get(sheet.getAssigneeId());
      result.add(
          new SheetBrief(
              sheet.getId(),
              sheet.getTaskSupplierId(),
              supplierId == null ? -1 : supplierId,
              supplier == null ? null : supplier.code(),
              supplier == null ? null : supplier.name(),
              sheet.getDimensionId(),
              dimensionNames.getOrDefault(sheet.getDimensionId(), "维度#" + sheet.getDimensionId()),
              sheet.getAssigneeId(),
              assignee == null ? null : assignee.name(),
              sheet.getStatus(),
              sheet.getVersion() == null ? 0 : sheet.getVersion()));
    }
    return result;
  }

  private static TaskSummary toSummary(EvalTask task) {
    return new TaskSummary(
        task.getId(),
        task.getName(),
        task.getYear(),
        task.getQuarter(),
        task.getTemplateVersionId(),
        task.getDeadline(),
        task.getStatus(),
        task.getVersion());
  }

  /** 创建草稿（01 J1）：任务 + 供应商勾选 + 审批链，同一事务。 */
  @Transactional
  public long createDraft(TaskDraftCommand command) {
    Validated validated = validate(command);

    EvalTask task = new EvalTask();
    task.setName(validated.name());
    task.setYear(command.year());
    task.setQuarter(command.quarter());
    task.setTemplateVersionId(command.templateVersionId());
    task.setDeadline(command.deadline());
    task.setStatus(EvalTaskStatus.DRAFT);
    task.setVersion(0);
    taskMapper.insert(task);

    replaceSuppliers(task.getId(), validated.supplierIds());
    replaceApprovers(task.getId(), validated.approverIds());

    auditFacade.record(
        AuditAction.TASK_CREATE,
        AuditTargetType.TASK,
        task.getId(),
        Map.of(
            "year", command.year(),
            "quarter", command.quarter(),
            "templateVersionId", command.templateVersionId(),
            "suppliers", validated.supplierIds().size(),
            "approvers", validated.approverIds().size()));
    return task.getId();
  }

  /**
   * 编辑草稿（01 J1；13 §E20 的口径）。
   *
   * <p>0 行的两种含义在这里区分：任务已不是草稿 ⇒ **2020**（状态本就不允许）；状态仍是草稿但
   * `version` 对不上 ⇒ **3001**（并发下别人先改了）。两条都由 `findById` 复读一次定夺——
   * 判别只在失败路径上多一次查询，正常路径零成本。
   */
  @Transactional
  public void updateDraft(long taskId, int version, TaskDraftCommand command) {
    Validated validated = validate(command);

    int rows =
        taskMapper.updateDraft(
            taskId,
            version,
            validated.name(),
            command.year(),
            command.quarter(),
            command.templateVersionId(),
            command.deadline());
    if (rows == 0) {
      throw statusOrConflict(taskId);
    }
    replaceSuppliers(taskId, validated.supplierIds());
    replaceApprovers(taskId, validated.approverIds());

    auditFacade.record(
        AuditAction.TASK_UPDATE,
        AuditTargetType.TASK,
        taskId,
        Map.of(
            "suppliers", validated.supplierIds().size(),
            "approvers", validated.approverIds().size()));
  }

  /**
   * 发布（T1；05 §5.5 T1 / 01 J2）：单事务里做五件事——状态守卫、J4 校验、批量生成评分单、
   * 自动指派、发 `TaskPublishedEvent`。
   *
   * <p>⚠️ `J4 的唯一性没有数据库兜底`（13 §E10）：`status <> 'CANCELED'` 是条件唯一，唯一键表达不了。
   * 这里做的是**当前读**校验，把窗口压到"两个发布事务同时开始"——**不是根除**。这条风险登记在
   * `11 §9` 与 `13 §R1`。
   */
  @Transactional
  public PublishResult publish(long taskId, int version) {
    EvalTask task =
        taskMapper.findByIdForUpdate(taskId).orElseThrow(() -> notFound());
    if (task.getStatus() != EvalTaskStatus.DRAFT) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID, "只有草稿可以发布，当前状态：" + task.getStatus());
    }
    if (task.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    List<TaskSupplier> taskSuppliers = taskSupplierMapper.findByTaskId(taskId);
    if (taskSuppliers.isEmpty()) {
      throw new BizException(ErrorCode.TASK_SUPPLIER_EMPTY, "任务还没有选择供应商");
    }
    List<ApprovalNode> nodes = approvalNodeMapper.findByTaskId(taskId);
    if (nodes.isEmpty()) {
      throw new BizException(ErrorCode.TASK_SUPPLIER_EMPTY, "审批链至少需要一级");
    }
    requireApproversEnabled(nodes);

    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    requireNoOtherTaskUsesSameSupplierInPeriod(task, taskSuppliers);

    List<Sheet> sheets = new ArrayList<>();
    Map<String, Long> assigneeByDepartment = new HashMap<>();
    int assigned = 0;
    int unassigned = 0;
    for (TaskSupplier taskSupplier : taskSuppliers) {
      for (DimensionSnapshot dimension : snapshot.dimensions()) {
        Long assigneeId = resolveAssignee(dimension.suggestedDept(), assigneeByDepartment);
        if (assigneeId == null) {
          unassigned++;
        } else {
          assigned++;
        }
        Sheet sheet = new Sheet();
        sheet.setTaskSupplierId(taskSupplier.getId());
        sheet.setDimensionId(dimension.id());
        sheet.setAssigneeId(assigneeId);
        sheet.setStatus(assigneeId == null ? SheetStatus.UNASSIGNED : SheetStatus.SCORING);
        sheet.setVersion(0);
        sheets.add(sheet);
      }
    }
    try {
      sheetMapper.insertBatch(sheets);
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      // uk_sheet(task_supplier_id, dimension_id)：并发发布时后到的那个会撞上——翻译成 3001 而不是漏成 5xxx
      // （13 §E10 的要求：**没有数据库之外的兜底**，所以这条翻译不能省）
      throw duplicateAsConflict();
    }

    int published = taskMapper.publish(taskId, version);
    if (published == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    PublishResult result =
        new PublishResult(taskSuppliers.size(), sheets.size(), assigned, unassigned);
    auditFacade.record(
        AuditAction.TASK_PUBLISH,
        AuditTargetType.TASK,
        taskId,
        Map.of(
            "suppliers", result.supplierCount(),
            "sheets", result.sheetCount(),
            "assigned", result.assignedCount(),
            "unassigned", result.unassignedCount()));
    eventPublisher.publish(
        new TaskPublishedEvent(
            taskId,
            task.getName(),
            task.getYear(),
            task.getQuarter(),
            sheets.stream()
                .map(sheet -> new TaskPublishedEvent.SheetAssignment(sheet.getId(), sheet.getAssigneeId()))
                .toList()));
    return result;
  }

  /**
   * 改派（01 J2 / 13 §E13）：只允许「待指派 / 评分中」，且**不换单、不清数据**——改的只是
   * `s.assignee_id`（评分记录挂在 sheet id 上，天然不受影响）。
   */
  @Transactional
  public void reassign(long sheetId, long assigneeId, int version) {
    Sheet sheet = sheetMapper.findByIdForUpdate(sheetId).orElseThrow(() -> sheetNotFound());
    requireEligibleAssignee(assigneeId);
    if (sheet.getStatus() != SheetStatus.UNASSIGNED && sheet.getStatus() != SheetStatus.SCORING) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID, "已提交的评分单不能改派，当前状态：" + sheet.getStatus());
    }
    int rows = sheetMapper.reassign(sheetId, assigneeId, version);
    if (rows == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("sheetId", sheetId);
    detail.put("from", sheet.getAssigneeId());
    detail.put("to", assigneeId);
    auditFacade.record(AuditAction.SHEET_ASSIGN, AuditTargetType.TASK, sheet.getTaskSupplierId(), detail);
  }

  /** 我的待办（01 E1 / 13 §E13）：只列指派给我的评分单。名称两次批量补齐，不逐行回查。 */
  @Transactional(readOnly = true)
  public List<MySheetView> findMySheets(long operatorId) {
    List<MySheetRow> rows = sheetMapper.findMySheets(operatorId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, SupplierBrief> suppliers = new HashMap<>();
    supplierQueryFacade
        .findByIds(rows.stream().map(MySheetRow::getSupplierId).distinct().toList())
        .forEach(brief -> suppliers.put(brief.id(), brief));

    Map<Long, Map<Long, String>> dimensionNamesByVersion = new HashMap<>();
    List<MySheetView> views = new ArrayList<>(rows.size());
    for (MySheetRow row : rows) {
      SupplierBrief supplier = suppliers.get(row.getSupplierId());
      String dimensionName =
          dimensionNamesByVersion
              .computeIfAbsent(
                  row.getTemplateVersionId(),
                  versionId ->
                      templateSnapshotFacade
                          .findPublished(versionId)
                          .map(TaskService::dimensionNames)
                          .orElseGet(Map::of))
              .getOrDefault(row.getDimensionId(), "维度#" + row.getDimensionId());
      views.add(
          new MySheetView(
              row.getSheetId(),
              row.getTaskId(),
              row.getTaskName(),
              row.getDeadline(),
              row.getTaskSupplierId(),
              row.getSupplierId(),
              supplier == null ? null : supplier.code(),
              supplier == null ? null : supplier.name(),
              row.getDimensionId(),
              dimensionName,
              SheetStatus.valueOf(row.getStatus()),
              row.getVersion() == null ? 0 : row.getVersion()));
    }
    return views;
  }

  /**
   * 第二层可见性（13 §E13）：这张评分单是不是指派给我的。**在 Service 内**，不靠前端隐藏入口。
   *
   * <p>越权一律 4xxx（`SHEET_NOT_ACCESSIBLE`）而不是 2xxx：参数合法，是"你无权访问这一行"（13 §E20）。
   */
  @Transactional(readOnly = true)
  public Sheet requireReadableSheet(long sheetId, long operatorId) {
    Sheet sheet = sheetMapper.findById(sheetId).orElseThrow(() -> sheetNotFound());
    if (sheet.getAssigneeId() == null || sheet.getAssigneeId() != operatorId) {
      throw new BizException(ErrorCode.SHEET_NOT_ACCESSIBLE);
    }
    return sheet;
  }

  // ---------- 内部 ----------

  private record Validated(String name, List<Long> supplierIds, List<Long> approverIds) {}

  private Validated validate(TaskDraftCommand command) {
    String name = command.name() == null ? "" : command.name().trim();
    if (name.isEmpty() || name.length() > 100) {
      throw new BizException(ErrorCode.BAD_REQUEST, "任务名称不能为空且不超过 100 字");
    }
    if (command.quarter() < 1 || command.quarter() > 4) {
      throw new BizException(ErrorCode.BAD_REQUEST, "季度必须在 1~4 之间");
    }
    if (command.year() < 2000 || command.year() > 2999) {
      throw new BizException(ErrorCode.BAD_REQUEST, "年份不合法：" + command.year());
    }
    if (command.deadline() == null) {
      throw new BizException(ErrorCode.BAD_REQUEST, "截止日期不能为空");
    }
    // 模板版本必须是**已发布**的（01 J1）：requirePublished 拿不到就抛 2010，不返回半成品（05 ADR-3）
    templateSnapshotFacade.requirePublished(command.templateVersionId());

    List<Long> supplierIds = distinct(command.supplierIds());
    if (supplierIds.isEmpty()) {
      throw new BizException(ErrorCode.TASK_SUPPLIER_EMPTY, "请至少选择一个供应商");
    }
    List<Long> approverIds = distinct(command.approverIds());
    if (approverIds.isEmpty()) {
      throw new BizException(ErrorCode.TASK_SUPPLIER_EMPTY, "审批链至少需要一级");
    }
    requireSuppliersEnabled(supplierIds);
    requireUsersEnabled(approverIds);
    return new Validated(name, supplierIds, approverIds);
  }

  /** 停用的供应商不可被新任务选中（01 S2；门面在 P1 就为这条留了 `isEnabled` 的语义）。 */
  private void requireSuppliersEnabled(List<Long> supplierIds) {
    List<SupplierBrief> suppliers = supplierQueryFacade.findByIds(supplierIds);
    Map<Long, SupplierBrief> byId = new LinkedHashMap<>();
    suppliers.forEach(brief -> byId.put(brief.id(), brief));
    List<String> bad = new ArrayList<>();
    for (Long id : supplierIds) {
      SupplierBrief brief = byId.get(id);
      if (brief == null || !brief.enabled()) {
        bad.add(brief == null ? String.valueOf(id) : brief.code());
      }
    }
    if (!bad.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "以下供应商不存在或已停用：" + String.join("、", bad));
    }
  }

  private void requireUsersEnabled(List<Long> userIds) {
    List<UserBrief> users = userQueryFacade.findByIds(userIds);
    Map<Long, UserBrief> byId = new LinkedHashMap<>();
    users.forEach(brief -> byId.put(brief.id(), brief));
    for (Long id : userIds) {
      UserBrief brief = byId.get(id);
      if (brief == null || brief.status() != UserStatus.ENABLED) {
        throw new BizException(ErrorCode.USER_NOT_ELIGIBLE, "审批人不存在或已停用：id=" + id);
      }
    }
  }

  private void requireApproversEnabled(List<ApprovalNode> nodes) {
    requireUsersEnabled(nodes.stream().map(ApprovalNode::getApproverId).toList());
  }

  private void requireEligibleAssignee(long assigneeId) {
    UserBrief user =
        userQueryFacade.findById(assigneeId).orElseThrow(() -> new BizException(ErrorCode.USER_NOT_ELIGIBLE));
    if (user.status() != UserStatus.ENABLED
        || (user.role() != Role.EVALUATOR && user.role() != Role.MANAGER)) {
      throw new BizException(
          ErrorCode.USER_NOT_ELIGIBLE, "改派对象必须是在职的评估人或采购经理：id=" + assigneeId);
    }
  }

  private void replaceSuppliers(long taskId, List<Long> supplierIds) {
    taskSupplierMapper.deleteByTaskId(taskId);
    List<TaskSupplier> rows = new ArrayList<>(supplierIds.size());
    for (Long supplierId : supplierIds) {
      TaskSupplier row = new TaskSupplier();
      row.setTaskId(taskId);
      row.setSupplierId(supplierId);
      row.setStatus(com.spe.context.evaluation.api.TaskSupplierStatus.SCORING);
      row.setCurrentNode(0);
      row.setVersion(0);
      rows.add(row);
    }
    try {
      taskSupplierMapper.insertBatch(rows);
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      // uk_task_supplier(task_id, supplier_id)：同一任务重复勾选同一供应商（正常情况下已被 distinct 拦掉，
      // 这里是并发/脏输入的兜底）⇒ 3001，不是 5xxx
      throw duplicateAsConflict();
    }
  }

  private void replaceApprovers(long taskId, List<Long> approverIds) {
    approvalNodeMapper.deleteByTaskId(taskId);
    List<ApprovalNode> rows = new ArrayList<>(approverIds.size());
    int seq = 1;
    for (Long approverId : approverIds) {
      ApprovalNode node = new ApprovalNode();
      node.setTaskId(taskId);
      node.setApproverId(approverId);
      node.setSeq(seq++);
      rows.add(node);
    }
    approvalNodeMapper.insertBatch(rows);
  }

  /**
   * 01 J4 的当前读校验（13 §E10 方案 A）：先把"同周期的其它非取消任务"锁住（当前读），
   * **再**用普通读查这些任务里有没有本次勾选的供应商。
   *
   * <p>顺序不能颠倒：read view 由第一条一致性读建立，锁定读不算——所以这条普通读看到的是**此刻**的数据，
   * 而不是事务开始时的快照（CLAUDE 默认约定第 6 条）。
   */
  private void requireNoOtherTaskUsesSameSupplierInPeriod(
      EvalTask task, List<TaskSupplier> taskSuppliers) {
    List<Long> otherTaskIds =
        taskMapper.lockOtherTaskIdsInPeriod(task.getYear(), task.getQuarter(), task.getId());
    if (otherTaskIds.isEmpty()) {
      return;
    }
    List<Long> supplierIds = taskSuppliers.stream().map(TaskSupplier::getSupplierId).toList();
    List<Long> used = taskSupplierMapper.findUsedSupplierIds(otherTaskIds, supplierIds);
    if (!used.isEmpty()) {
      throw new BizException(
          ErrorCode.TASK_PERIOD_DUPLICATE,
          "以下供应商在 " + task.getYear() + "-Q" + task.getQuarter() + " 已有评估任务："
              + used.stream().map(String::valueOf).toList());
    }
  }

  /**
   * 自动指派（01 J2 / 13 §E12）：建议部门下**在职**且角色 ∈ {EVALUATOR, MANAGER} 的用户里取
   * **id 最小者**；部门为空或没有人 ⇒ 未指派（不猜部门）。
   *
   * <p>确定性是刻意的："轮流分配"会把指派变成有状态的策略，且让集成用例与演示不可复现（13 §E12）。
   * 同一个部门在一次发布里只查一次库（部门数 ≤ 维度数）。
   */
  private Long resolveAssignee(String suggestedDept, Map<String, Long> cache) {
    if (suggestedDept == null || suggestedDept.isBlank()) {
      return null;
    }
    // 维度存的是**中文标签**（V8 种子：'质量'/'交付物流'），用户档案存的是**枚举 code** ⇒ 先换算再查人。
    // 不换算的表现是"所有评分单都未指派"而不报错（04-088），所以这里必须显式解析、解析不了就记日志。
    java.util.Optional<Department> department = Department.resolve(suggestedDept);
    if (department.isEmpty()) {
      log.warn("自动指派：维度建议部门 '{}' 不是已知部门（既不是 code 也不是标签）⇒ 置为待指派", suggestedDept);
      return null;
    }
    String deptCode = department.get().name();
    return cache.computeIfAbsent(
        deptCode,
        dept -> {
          List<Long> candidates =
              userQueryFacade.findEnabledIdsByDepartmentAndRoles(dept, ASSIGNABLE_ROLES);
          if (candidates.isEmpty()) {
            log.info("自动指派：部门 {} 下没有在职的评估人/采购经理 ⇒ 该维度置为待指派", dept);
            return null;
          }
          log.info("自动指派：部门 {} 有 {} 人，指派给 id={}", dept, candidates.size(), candidates.get(0));
          return candidates.get(0);
        });
  }

  private BizException statusOrConflict(long taskId) {
    EvalTask current = taskMapper.findById(taskId).orElseThrow(TaskService::notFound);
    if (current.getStatus() != EvalTaskStatus.DRAFT) {
      return new BizException(
          ErrorCode.TASK_STATUS_INVALID, "只有草稿可以编辑，当前状态：" + current.getStatus());
    }
    return new BizException(ErrorCode.CONCURRENT_CONFLICT);
  }

  private static Map<Long, String> dimensionNames(TemplateSnapshot snapshot) {
    Map<Long, String> names = new HashMap<>();
    for (DimensionSnapshot dimension : snapshot.dimensions()) {
      names.put(dimension.id(), dimension.name());
    }
    return names;
  }

  private static List<Long> distinct(List<Long> ids) {
    if (ids == null) {
      return List.of();
    }
    Set<Long> distinct = new LinkedHashSet<>();
    ids.stream().filter(java.util.Objects::nonNull).forEach(distinct::add);
    return List.copyOf(distinct);
  }

  /** 唯一键冲突 → 3001（06 §2.6：并发冲突走 3xxx，不泄漏成 5xxx）。 */
  private static BizException duplicateAsConflict() {
    return new BizException(ErrorCode.CONCURRENT_CONFLICT, "该任务已生成过评估单或评分单，请刷新后重试");
  }

  private static BizException notFound() {
    return new BizException(ErrorCode.TASK_NOT_FOUND);
  }

  private static BizException sheetNotFound() {
    return new BizException(ErrorCode.SHEET_NOT_FOUND);
  }
}
