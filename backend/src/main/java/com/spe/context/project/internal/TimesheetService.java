package com.spe.context.project.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.project.api.events.TimesheetPostedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 项目工时：DRAFT→SUBMITTED→APPROVED，审批时校验预算并写项目成本（docs/erp/38 §2）。 */
@Service
@RequiredArgsConstructor
public class TimesheetService {

  private final ProjectTimesheetMapper timesheetMapper;
  private final TimesheetLineMapper lineMapper;
  private final ProjectMapper projectMapper;
  private final ProjectWbsMapper wbsMapper;
  private final ProjectCostMapper costMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public TimesheetView create(long userId, long companyId, List<LineCommand> commands) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.TIMESHEET_STATUS_INVALID, "至少需要一行工时");
    }
    ProjectTimesheet timesheet = new ProjectTimesheet();
    timesheet.setTimesheetNo(ProjectSupport.docNo("TS"));
    timesheet.setCompanyId(companyId);
    timesheet.setUserId(userId);
    timesheet.setStatus(TimesheetStatus.DRAFT);
    timesheet.setVersion(0);
    timesheetMapper.insert(timesheet);

    BigDecimal totalHours = BigDecimal.ZERO;
    BigDecimal totalAmount = BigDecimal.ZERO;
    int lineNo = 1;
    for (LineCommand command : commands) {
      Project project =
          projectMapper
              .findById(command.projectId())
              .filter(p ->
                  p.getStatus() == ProjectStatus.ACTIVE && p.getCompanyId() == companyId)
              .orElseThrow(() -> new BizException(ErrorCode.PROJECT_STATUS_INVALID, "项目未激活"));
      if (command.wbsId() != null) {
        wbsMapper
            .findByIdAndProject(command.wbsId(), project.getId())
            .filter(wbs -> wbs.getStatus() == WbsStatus.OPEN)
            .orElseThrow(() -> new BizException(ErrorCode.PROJECT_WBS_INVALID));
      }
      if (command.hours() == null
          || command.hours().signum() <= 0
          || command.hours().compareTo(new BigDecimal("24")) > 0) {
        throw new BizException(ErrorCode.TIMESHEET_STATUS_INVALID, "单日工时必须在 0~24 之间");
      }
      if (command.workDate() == null) {
        throw new BizException(ErrorCode.TIMESHEET_STATUS_INVALID, "工作日期不能为空");
      }
      BigDecimal hours = command.hours().setScale(2, RoundingMode.HALF_UP);
      BigDecimal rate = project.getDefaultLaborRate();
      BigDecimal amount = hours.multiply(rate).setScale(4, RoundingMode.HALF_UP);
      TimesheetLine line = new TimesheetLine();
      line.setTimesheetId(timesheet.getId());
      line.setLineNo(lineNo++);
      line.setProjectId(project.getId());
      line.setWbsId(command.wbsId());
      line.setWorkDate(command.workDate());
      line.setHours(hours);
      line.setRate(rate);
      line.setAmount(amount);
      lineMapper.insert(line);
      totalHours = totalHours.add(hours);
      totalAmount = totalAmount.add(amount);
    }
    timesheet.setTotalHours(totalHours);
    timesheet.setTotalAmount(totalAmount);
    timesheetMapper.updateById(timesheet);
    auditFacade.record(
        AuditAction.TIMESHEET_CREATE,
        AuditTargetType.TIMESHEET,
        timesheet.getId(),
        Map.of(
            "timesheetNo", timesheet.getTimesheetNo(),
            "lineCount", commands.size(),
            "hours", totalHours,
            "amount", totalAmount));
    return detail(timesheet.getId());
  }

  @Transactional
  public TimesheetView submit(long id) {
    ProjectTimesheet timesheet =
        timesheetMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.TIMESHEET_NOT_FOUND));
    if (timesheetMapper.markSubmitted(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.TIMESHEET_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.TIMESHEET_SUBMIT,
        AuditTargetType.TIMESHEET,
        id,
        Map.of("timesheetNo", timesheet.getTimesheetNo()));
    return detail(id);
  }

  @Transactional
  public TimesheetView approve(long id, long operatorId) {
    ProjectTimesheet timesheet =
        timesheetMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.TIMESHEET_NOT_FOUND));
    if (timesheet.getStatus() != TimesheetStatus.SUBMITTED) {
      throw new BizException(ErrorCode.TIMESHEET_STATUS_INVALID);
    }
    if (timesheet.getUserId() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    List<TimesheetLine> lines = lineMapper.findByTimesheetId(id);
    Map<Long, BigDecimal> byProject = new LinkedHashMap<>();
    for (TimesheetLine line : lines) {
      byProject.merge(line.getProjectId(), line.getAmount(), BigDecimal::add);
    }
    for (Map.Entry<Long, BigDecimal> entry : byProject.entrySet()) {
      Project project =
          projectMapper
              .findById(entry.getKey())
              .orElseThrow(() -> new BizException(ErrorCode.PROJECT_NOT_FOUND));
      if (project.getStatus() != ProjectStatus.ACTIVE) {
        throw new BizException(ErrorCode.PROJECT_STATUS_INVALID, "项目未激活");
      }
      BigDecimal committed = costMapper.sumByProject(project.getId());
      if (committed.add(entry.getValue()).compareTo(project.getBudgetAmount()) > 0) {
        throw new BizException(
            ErrorCode.PROJECT_BUDGET_EXCEEDED,
            "项目 " + project.getProjectNo() + " 预算不足");
      }
    }
    String currency =
        masterDataQueryFacade
            .findCompany(timesheet.getCompanyId())
            .map(CompanyBrief::baseCurrency)
            .orElse("CNY");
    for (TimesheetLine line : lines) {
      ProjectCost cost = new ProjectCost();
      cost.setProjectId(line.getProjectId());
      cost.setSourceType("TIMESHEET");
      cost.setSourceId(id);
      cost.setSourceLineId(line.getId());
      cost.setAmount(line.getAmount());
      cost.setCurrency(currency);
      costMapper.insert(cost);
    }
    if (timesheetMapper.markApproved(id, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.TIMESHEET_STATUS_INVALID);
    }
    eventPublisher.publish(
        new TimesheetPostedEvent(
            id, timesheet.getCompanyId(), timesheet.getUserId(), timesheet.getTotalAmount(), currency));
    auditFacade.record(
        AuditAction.TIMESHEET_APPROVE,
        AuditTargetType.TIMESHEET,
        id,
        Map.of(
            "timesheetNo", timesheet.getTimesheetNo(),
            "hours", timesheet.getTotalHours(),
            "amount", timesheet.getTotalAmount()));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<ProjectTimesheet> page(
      int page, int size, Long companyId, Long userId, String status) {
    PageQuery query = PageQuery.of(page, size);
    TimesheetStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        timesheetMapper.findPage(companyId, userId, parsed, query.offset(), query.size()),
        timesheetMapper.countBy(companyId, userId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public TimesheetView detail(long id) {
    ProjectTimesheet timesheet =
        timesheetMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.TIMESHEET_NOT_FOUND));
    return new TimesheetView(
        timesheet.getId(),
        timesheet.getTimesheetNo(),
        timesheet.getCompanyId(),
        timesheet.getUserId(),
        timesheet.getStatus(),
        timesheet.getTotalHours(),
        timesheet.getTotalAmount(),
        timesheet.getCreatedAt(),
        timesheet.getSubmittedAt(),
        timesheet.getApprovedAt(),
        timesheet.getVersion(),
        lineMapper.findByTimesheetId(id).stream()
            .map(
                line ->
                    new TimesheetView.Line(
                        line.getId(),
                        line.getLineNo(),
                        line.getProjectId(),
                        line.getWbsId(),
                        line.getWorkDate(),
                        line.getHours(),
                        line.getRate(),
                        line.getAmount()))
            .toList());
  }

  private static TimesheetStatus parseStatus(String status) {
    try {
      return TimesheetStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "工时状态不合法：" + status);
    }
  }

  /** 工时行命令。 */
  public record LineCommand(long projectId, Long wbsId, LocalDate workDate, BigDecimal hours) {}
}
