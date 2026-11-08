package com.spe.context.project.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.project.api.events.ProjectApprovedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 项目/WBS/预算（docs/erp/38 §2）。 */
@Service
@RequiredArgsConstructor
public class ProjectService {

  private final ProjectMapper projectMapper;
  private final ProjectWbsMapper wbsMapper;
  private final ProjectBudgetMapper budgetMapper;
  private final ProjectCostMapper costMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public ProjectView create(
      long operatorId,
      long companyId,
      Long customerId,
      String name,
      String currency,
      BigDecimal defaultLaborRate,
      LocalDate startDate,
      LocalDate endDate,
      Long managerId) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    if (customerId != null) {
      masterDataQueryFacade
          .findCustomer(customerId)
          .filter(CustomerBrief::active)
          .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_NOT_FOUND));
    }
    if (name == null || name.isBlank()) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID, "项目名称不能为空");
    }
    String normalizedCurrency =
        currency == null || currency.isBlank()
            ? company.baseCurrency()
            : currency.trim().toUpperCase();
    if (!company.baseCurrency().equals(normalizedCurrency)) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID, "E5 暂不支持多币种项目");
    }
    if (defaultLaborRate != null && defaultLaborRate.signum() < 0) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID, "人工费率不能为负");
    }
    if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID, "项目结束日期不能早于开始日期");
    }
    Project project = new Project();
    project.setProjectNo(ProjectSupport.docNo("PRJ"));
    project.setCompanyId(companyId);
    project.setCustomerId(customerId);
    project.setName(name.trim());
    project.setCurrency(normalizedCurrency);
    project.setBudgetAmount(BigDecimal.ZERO);
    project.setDefaultLaborRate(defaultLaborRate == null ? BigDecimal.ZERO : defaultLaborRate);
    project.setStatus(ProjectStatus.DRAFT);
    project.setStartDate(startDate);
    project.setEndDate(endDate);
    project.setManagerId(managerId == null ? operatorId : managerId);
    project.setCreatedBy(operatorId);
    project.setVersion(0);
    projectMapper.insert(project);
    auditFacade.record(
        AuditAction.PROJECT_CREATE,
        AuditTargetType.PROJECT,
        project.getId(),
        Map.of("projectNo", project.getProjectNo(), "name", project.getName()));
    return detail(project.getId());
  }

  @Transactional
  public ProjectView approve(long id, long operatorId) {
    Project project = lock(id);
    if (project.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    if (projectMapper.markApproved(id, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID);
    }
    eventPublisher.publish(
        new ProjectApprovedEvent(id, project.getCompanyId(), project.getProjectNo()));
    auditFacade.record(
        AuditAction.PROJECT_APPROVE,
        AuditTargetType.PROJECT,
        id,
        Map.of("projectNo", project.getProjectNo()));
    return detail(id);
  }

  @Transactional
  public ProjectView activate(long id) {
    Project project = lock(id);
    if (projectMapper.markActive(id) == 0) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.PROJECT_ACTIVATE,
        AuditTargetType.PROJECT,
        id,
        Map.of("projectNo", project.getProjectNo()));
    return detail(id);
  }

  @Transactional
  public ProjectView close(long id) {
    Project project = lock(id);
    if (projectMapper.markClosed(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.PROJECT_CLOSE,
        AuditTargetType.PROJECT,
        id,
        Map.of("projectNo", project.getProjectNo()));
    return detail(id);
  }

  @Transactional
  public ProjectView addWbs(
      long projectId, Long parentId, String code, String name, BigDecimal budget) {
    Project project =
        projectMapper
            .findById(projectId)
            .orElseThrow(() -> new BizException(ErrorCode.PROJECT_NOT_FOUND));
    if (project.getStatus() == ProjectStatus.CLOSED) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID);
    }
    if (parentId != null) {
      wbsMapper
          .findByIdAndProject(parentId, projectId)
          .orElseThrow(() -> new BizException(ErrorCode.PROJECT_WBS_INVALID));
    }
    if (code == null || code.isBlank() || name == null || name.isBlank()) {
      throw new BizException(ErrorCode.PROJECT_WBS_INVALID, "WBS 编码与名称不能为空");
    }
    ProjectWbs wbs = new ProjectWbs();
    wbs.setProjectId(projectId);
    wbs.setParentId(parentId);
    wbs.setCode(code.trim().toUpperCase());
    wbs.setName(name.trim());
    wbs.setBudget(budget == null ? BigDecimal.ZERO : budget);
    wbs.setStatus(WbsStatus.OPEN);
    wbsMapper.insert(wbs);
    return detail(projectId);
  }

  @Transactional
  public ProjectView createBudget(
      long operatorId,
      long projectId,
      String versionNo,
      BigDecimal labor,
      BigDecimal purchase,
      BigDecimal expense,
      BigDecimal other) {
    Project project =
        projectMapper
            .findById(projectId)
            .orElseThrow(() -> new BizException(ErrorCode.PROJECT_NOT_FOUND));
    if (project.getStatus() == ProjectStatus.CLOSED) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID);
    }
    BigDecimal total =
        zero(labor).add(zero(purchase)).add(zero(expense)).add(zero(other));
    ProjectBudget budget = new ProjectBudget();
    budget.setProjectId(projectId);
    budget.setVersionNo(
        versionNo == null || versionNo.isBlank() ? "V1" : versionNo.trim().toUpperCase());
    budget.setLaborAmount(zero(labor));
    budget.setPurchaseAmount(zero(purchase));
    budget.setExpenseAmount(zero(expense));
    budget.setOtherAmount(zero(other));
    budget.setTotalAmount(total);
    budget.setStatus(ProjectBudgetStatus.DRAFT);
    budget.setCreatedBy(operatorId);
    budgetMapper.insert(budget);
    return detail(projectId);
  }

  @Transactional
  public ProjectView approveBudget(long budgetId, long operatorId) {
    ProjectBudget budget =
        budgetMapper
            .lockById(budgetId)
            .orElseThrow(() -> new BizException(ErrorCode.PROJECT_NOT_FOUND, "预算不存在"));
    if (budgetMapper.markApproved(budgetId, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.PROJECT_STATUS_INVALID);
    }
    projectMapper.updateBudget(budget.getProjectId(), budget.getTotalAmount());
    auditFacade.record(
        AuditAction.PROJECT_BUDGET_APPROVE,
        AuditTargetType.PROJECT_BUDGET,
        budgetId,
        Map.of("projectId", budget.getProjectId(), "total", budget.getTotalAmount()));
    return detail(budget.getProjectId());
  }

  @Transactional(readOnly = true)
  public PageResult<Project> page(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    ProjectStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        projectMapper.findPage(companyId, parsed, query.offset(), query.size()),
        projectMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public ProjectView detail(long id) {
    Project project =
        projectMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.PROJECT_NOT_FOUND));
    return new ProjectView(
        project.getId(),
        project.getProjectNo(),
        project.getCompanyId(),
        project.getCustomerId(),
        project.getName(),
        project.getCurrency(),
        project.getBudgetAmount(),
        project.getDefaultLaborRate(),
        project.getStatus(),
        project.getStartDate(),
        project.getEndDate(),
        project.getManagerId(),
        project.getCreatedAt(),
        project.getApprovedAt(),
        project.getClosedAt(),
        project.getVersion(),
        costMapper.sumByProject(id),
        wbsMapper.findByProjectId(id).stream()
            .map(
                wbs ->
                    new ProjectView.Wbs(
                        wbs.getId(),
                        wbs.getParentId(),
                        wbs.getCode(),
                        wbs.getName(),
                        wbs.getBudget(),
                        wbs.getStatus()))
            .toList(),
        budgetMapper.findByProjectId(id).stream()
            .map(
                budget ->
                    new ProjectView.Budget(
                        budget.getId(),
                        budget.getVersionNo(),
                        budget.getLaborAmount(),
                        budget.getPurchaseAmount(),
                        budget.getExpenseAmount(),
                        budget.getOtherAmount(),
                        budget.getTotalAmount(),
                        budget.getStatus()))
            .toList());
  }

  @Transactional(readOnly = true)
  public BigDecimal actualCost(long projectId) {
    return costMapper.sumByProject(projectId);
  }

  private Project lock(long id) {
    return projectMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.PROJECT_NOT_FOUND));
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static ProjectStatus parseStatus(String status) {
    try {
      return ProjectStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "项目状态不合法：" + status);
    }
  }
}
