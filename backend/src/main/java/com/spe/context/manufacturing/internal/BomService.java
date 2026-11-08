package com.spe.context.manufacturing.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BOM 版本（docs/erp/36）：DRAFT 可激活，激活时停用同物料旧版本。 */
@Service
@RequiredArgsConstructor
public class BomService {

  private final BomHeaderMapper headerMapper;
  private final BomLineMapper lineMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public BomView create(
      long operatorId, long companyId, long itemId, String version, List<BomLineCommand> commands) {
    masterDataQueryFacade
        .findCompany(companyId)
        .filter(com.spe.context.masterdata.api.CompanyBrief::active)
        .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    ItemBrief item =
        masterDataQueryFacade
            .findItem(itemId)
            .filter(ItemBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.BOM_STATUS_INVALID, "BOM 至少需要一行");
    }
    String normalizedVersion = version == null || version.isBlank() ? "V1" : version.trim();
    BomHeader header = new BomHeader();
    header.setBomNo(ManufacturingSupport.docNo("BOM"));
    header.setCompanyId(companyId);
    header.setItemId(itemId);
    header.setVersion(normalizedVersion);
    header.setStatus(BomStatus.DRAFT);
    header.setCreatedBy(operatorId);
    headerMapper.insert(header);
    Set<Long> seen = new HashSet<>();
    int lineNo = 1;
    for (BomLineCommand command : commands) {
      if (!seen.add(command.componentItemId())) {
        throw new BizException(ErrorCode.BOM_STATUS_INVALID, "同一组件不能重复");
      }
      ItemBrief component =
          masterDataQueryFacade
              .findItem(command.componentItemId())
              .filter(ItemBrief::active)
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.BOM_STATUS_INVALID, "组件用量必须大于 0");
      }
      BomLine line = new BomLine();
      line.setBomId(header.getId());
      line.setLineNo(lineNo++);
      line.setComponentItemId(component.id());
      line.setQty(ManufacturingSupport.qty(command.qty()));
      line.setUom(
          command.uom() == null || command.uom().isBlank()
              ? component.baseUom()
              : command.uom().trim().toUpperCase());
      lineMapper.insert(line);
    }
    auditFacade.record(
        AuditAction.BOM_CREATE,
        AuditTargetType.BOM,
        header.getId(),
        Map.of("bomNo", header.getBomNo(), "itemId", itemId, "lineCount", commands.size()));
    return detail(header.getId());
  }

  @Transactional
  public BomView activate(long id) {
    BomHeader header =
        headerMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BOM_NOT_FOUND));
    if (header.getStatus() != BomStatus.DRAFT) {
      throw new BizException(ErrorCode.BOM_STATUS_INVALID);
    }
    headerMapper.deactivateOthers(header.getCompanyId(), header.getItemId(), id);
    headerMapper.markActive(id, LocalDateTime.now(ZoneOffset.UTC));
    auditFacade.record(
        AuditAction.BOM_ACTIVATE,
        AuditTargetType.BOM,
        id,
        Map.of("bomNo", header.getBomNo(), "version", header.getVersion()));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<BomHeader> page(int page, int size, long companyId) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        headerMapper.findPage(companyId, query.offset(), query.size()),
        headerMapper.countBy(companyId),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public BomView detail(long id) {
    BomHeader header =
        headerMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BOM_NOT_FOUND));
    ItemBrief item = masterDataQueryFacade.findItem(header.getItemId()).orElse(null);
    List<BomLine> lines = lineMapper.findByBomId(id);
    Map<Long, ItemBrief> components =
        masterDataQueryFacade
            .findItems(lines.stream().map(BomLine::getComponentItemId).toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    return new BomView(
        header.getId(),
        header.getBomNo(),
        header.getCompanyId(),
        header.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        header.getVersion(),
        header.getStatus(),
        header.getCreatedAt(),
        header.getActivatedAt(),
        lines.stream()
            .map(
                line -> {
                  ItemBrief component = components.get(line.getComponentItemId());
                  return new BomLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getComponentItemId(),
                      component == null ? null : component.code(),
                      component == null ? null : component.name(),
                      line.getQty(),
                      line.getUom());
                })
            .toList());
  }

  /** BOM 行命令。 */
  public record BomLineCommand(long componentItemId, BigDecimal qty, String uom) {}
}
