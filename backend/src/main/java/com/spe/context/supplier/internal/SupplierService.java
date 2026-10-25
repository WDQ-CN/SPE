package com.spe.context.supplier.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商档案服务（01 S1/S2 / 10 §3 W2）。
 *
 * <p><b>事务形态</b>（05 §5.5 T6）：每个写方法都是单一 `@Transactional`，业务行变更与 `AuditFacade`
 * 留痕在同一事务内——审计写入失败则业务一并回滚（C4 有用例钉住这条语义）；Controller 不做任何拼装式写。
 *
 * <p><b>跨上下文</b>：本类是 supplier 上下文第一次真实依赖 `system.api`（`AuditFacade`），
 * 依赖方向由 ArchUnit R-A 守护（只允许 `..api..`）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

  private final SupplierMapper supplierMapper;
  private final AuditFacade auditFacade;

  /** 列表：`keyword`（编码或名称）与 `category` 可组合；**不按状态过滤**——停用后仍要能看到并重新启用。 */
  @Transactional(readOnly = true)
  public PageResult<SupplierSummary> page(int page, int size, String keyword, String category) {
    PageQuery query = PageQuery.of(page, size);
    // 用户输入按字面匹配（03 §41.3 的 P2-37）：`%`/`_` 是 LIKE 通配符，不转义会让搜索退化成全匹配
    String like = keyword == null || keyword.isBlank() ? null : SqlKeywords.escapeLike(keyword.trim());
    var rows =
        supplierMapper.findPage(like, category, query.offset(), query.size()).stream()
            .map(SupplierSummary::of)
            .toList();
    return PageResult.of(rows, supplierMapper.countBy(like, category), query.page(), query.size());
  }

  /** 新增：编码唯一前置校验走 2xxx，DB 唯一键是最后防线（06 §4）。新建一律 `ENABLED`。 */
  @Transactional
  public SupplierSummary create(String code, String name, String category) {
    if (supplierMapper.countByCode(code) > 0) {
      throw new BizException(ErrorCode.SUPPLIER_CODE_EXISTS);
    }

    Supplier supplier = new Supplier();
    supplier.setCode(code);
    supplier.setName(name);
    supplier.setCategory(category);
    supplier.setStatus(SupplierStatus.ENABLED);
    supplier.setVersion(0);
    try {
      supplierMapper.insert(supplier);
    } catch (DuplicateKeyException ex) {
      // 并发下「先查后插」仍可能撞上 uk_supplier_code：DB 才是最终防线，
      // 这里把约束冲突翻译成与前置校验一致的 2xxx，而不是漏成 5xxx
      throw new BizException(ErrorCode.SUPPLIER_CODE_EXISTS);
    }

    auditFacade.record(
        AuditAction.SUPPLIER_CREATE, AuditTargetType.SUPPLIER, supplier.getId(), createDetail(supplier));
    log.info("创建供应商 {}（id={}）", code, supplier.getId());
    return SupplierSummary.of(supplier);
  }

  /**
   * 编辑档案（编码/名称/分类；停用后仍可编辑，E19）。
   *
   * <p>先读一次是为了①是否存在（给 2008 而不是笼统冲突）②改码时的查重与审计的前后值；
   * **迁移本身仍是带 `version` 前置条件的 UPDATE**，不是「先 SELECT 判断再 UPDATE」那种竞态写法。
   */
  @Transactional
  public SupplierSummary update(long id, String code, String name, String category, int version) {
    Supplier current =
        supplierMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));

    // 改码也要查重（E17）；先比较再查，避免「只改名称」被自己那条记录挡住
    if (!current.getCode().equals(code) && supplierMapper.countByCode(code) > 0) {
      throw new BizException(ErrorCode.SUPPLIER_CODE_EXISTS);
    }
    boolean applied;
    try {
      applied = supplierMapper.updateProfile(id, code, name, category, version) == 1;
    } catch (DuplicateKeyException ex) {
      // 改码时前置的 countByCode 与实际 UPDATE 之间存在竞态窗口，DB 唯一键才是最后防线（06 §4）：
      // 与「新增」同口径翻译成 2xxx，不能因为走了另一条 SQL 就漏成 5xxx。
      throw new BizException(ErrorCode.SUPPLIER_CODE_EXISTS);
    }
    if (!applied) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), code);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "category", current.getCategory(), category);
    auditFacade.record(AuditAction.SUPPLIER_UPDATE, AuditTargetType.SUPPLIER, id, detail);

    log.info("编辑供应商 id={}，改动 {}", id, detail.keySet());
    // 同 UserService.update：不回查。字段来自入参或上面那次读，version 由 updateProfile 的 SQL
    // （`SET ..., version = version + 1`）决定，「编辑后 version 自增」另有集成测试钉住。
    return new SupplierSummary(id, code, name, category, current.getStatus(), version + 1);
  }

  /**
   * 启停（01 S2）：前置状态由目标状态反推（{@link SupplierStatus#other()}），**不查当前状态**；
   * 0 行时不写审计——业务失败不留下成功痕迹。
   *
   * <p>⚠️ 0 行有**两种**含义，必须分开（03 §38 的 P2-7）：供应商不存在 ⇒ 2008，确实存在但版本/状态对不上
   * ⇒ 3001。分类读发生在守卫 UPDATE **之后**、只负责把"0 行"拆开，不是 `06 §2.4` 禁的"先 SELECT 判断再
   * UPDATE"（那个的病是"判断基于快照、写不带条件"），与 `TaskService.updateDraft` 同形。
   * 两条都返回 3001 的表现是：前端提示"数据已被他人改动，请刷新"，用户对着一个不存在的目标反复重试。
   */
  @Transactional
  public void changeStatus(long id, SupplierStatus target, int version) {
    SupplierStatus expected = target.other();
    if (supplierMapper.updateStatusAndVersion(id, target, expected, version) == 0) {
      throw supplierMapper.findById(id).isEmpty()
          ? new BizException(ErrorCode.SUPPLIER_NOT_FOUND)
          : new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    auditFacade.record(
        AuditAction.SUPPLIER_STATUS_CHANGE,
        AuditTargetType.SUPPLIER,
        id,
        Map.of("from", expected.name(), "to", target.name()));
    log.info("供应商状态迁移 id={}：{} → {}", id, expected, target);
  }

  /** 新增的审计细节：只放可复述的业务字段（`category` 可为 null，LinkedHashMap 允许空值）。 */
  private static Map<String, Object> createDetail(Supplier supplier) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", supplier.getCode());
    detail.put("name", supplier.getName());
    detail.put("category", supplier.getCategory());
    return detail;
  }
}
