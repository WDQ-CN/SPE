package com.spe.context.supplier.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 供应商服务的行为锁定（10 §3 W3 的验收 A/B/C 与 03 §14 的处置）。
 *
 * <p>重点：①守卫 UPDATE 影响 0 行 → 3001 且**不写审计** ②编码唯一 → 2007（前置校验 + DB 兜底两条路）
 * ③同码编辑不得被自己挡住（短路求值，不查重）④停用前置状态由目标状态反推、不查库。
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

  @Mock private SupplierMapper supplierMapper;
  @Mock private AuditFacade auditFacade;

  private SupplierService supplierService;

  @BeforeEach
  void setUp() {
    supplierService = new SupplierService(supplierMapper, auditFacade);
  }

  private static Supplier supplier(long id, SupplierStatus status, int version, String category) {
    Supplier supplier = new Supplier();
    supplier.setId(id);
    supplier.setCode("SUP-001");
    supplier.setName("示例供应商");
    supplier.setCategory(category);
    supplier.setStatus(status);
    supplier.setVersion(version);
    return supplier;
  }

  @Test
  @DisplayName("编辑：改码撞上唯一键（前置校验与实际写入之间的竞态）→ 仍翻译成 2007，不漏成 5xxx")
  void updateTranslatesDuplicateKeyException() {
    when(supplierMapper.findById(2L)).thenReturn(Optional.of(supplier(2L, SupplierStatus.ENABLED, 1, null)));
    when(supplierMapper.countByCode("SUP-NEW")).thenReturn(0L);
    when(supplierMapper.updateProfile(2L, "SUP-NEW", "示例供应商", null, 1))
        .thenThrow(new DuplicateKeyException("uk_supplier_code"));

    assertThatThrownBy(() -> supplierService.update(2L, "SUP-NEW", "示例供应商", null, 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SUPPLIER_CODE_EXISTS);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("启停：守卫 UPDATE 影响 0 行 → 3001，且不写审计（业务失败不留成功痕迹）")
  void statusChangeConflictReturnsConflictWithoutAudit() {
    when(supplierMapper.updateStatusAndVersion(7L, SupplierStatus.DISABLED, SupplierStatus.ENABLED, 3))
        .thenReturn(0);

    assertThatThrownBy(() -> supplierService.changeStatus(7L, SupplierStatus.DISABLED, 3))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("启停：成功时审计记 from/to；前置状态由目标状态反推，过程中不查库")
  void statusChangeRecordsTransitionWithoutSelecting() {
    when(supplierMapper.updateStatusAndVersion(7L, SupplierStatus.ENABLED, SupplierStatus.DISABLED, 5))
        .thenReturn(1);

    supplierService.changeStatus(7L, SupplierStatus.ENABLED, 5);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade)
        .record(eq(AuditAction.SUPPLIER_STATUS_CHANGE), any(), eq(7L), detail.capture());
    assertThat(detail.getValue()).containsEntry("from", "DISABLED").containsEntry("to", "ENABLED");
    // 守卫条件里的前置状态来自目标状态的两态推导，没有「先查状态」的读取
    verify(supplierMapper, never()).findById(anyLong());
  }

  @Test
  @DisplayName("新增：编码已存在 → 2007，且不插库")
  void createRejectsDuplicateCode() {
    when(supplierMapper.countByCode("SUP-001")).thenReturn(1L);

    assertThatThrownBy(() -> supplierService.create("SUP-001", "示例供应商", "电子"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SUPPLIER_CODE_EXISTS);
    verify(supplierMapper, never()).insert(any(Supplier.class));
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("新增：并发撞上 DB 唯一键 → 仍翻译成 2007，不漏成 5xxx（06 §4 最后防线）")
  void createTranslatesDuplicateKeyException() {
    when(supplierMapper.countByCode("SUP-001")).thenReturn(0L);
    when(supplierMapper.insert(any(Supplier.class)))
        .thenThrow(new DuplicateKeyException("uk_supplier_code"));

    assertThatThrownBy(() -> supplierService.create("SUP-001", "示例供应商", "电子"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SUPPLIER_CODE_EXISTS);
  }

  @Test
  @DisplayName("新增：新建为 ENABLED、version=0，审计 detail 只含业务字段")
  void createPersistsEnabledAndAudits() {
    when(supplierMapper.countByCode("SUP-001")).thenReturn(0L);
    when(supplierMapper.insert(any(Supplier.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(0, Supplier.class).setId(9L);
              return 1;
            });

    SupplierSummary created = supplierService.create("SUP-001", "示例供应商", null);

    ArgumentCaptor<Supplier> inserted = ArgumentCaptor.forClass(Supplier.class);
    verify(supplierMapper).insert(inserted.capture());
    assertThat(inserted.getValue().getStatus()).isEqualTo(SupplierStatus.ENABLED);
    assertThat(inserted.getValue().getVersion()).isZero();
    assertThat(created.id()).isEqualTo(9L);
    assertThat(created.category()).isNull();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.SUPPLIER_CREATE), any(), eq(9L), detail.capture());
    assertThat(detail.getValue()).containsOnlyKeys("code", "name", "category");
  }

  @Test
  @DisplayName("编辑：不存在 → 2008；版本不符（守卫 0 行）→ 3001")
  void updateDistinguishesMissingAndConflict() {
    when(supplierMapper.findById(1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> supplierService.update(1L, "SUP-001", "改名", null, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);

    when(supplierMapper.findById(2L)).thenReturn(Optional.of(supplier(2L, SupplierStatus.ENABLED, 1, null)));
    when(supplierMapper.updateProfile(2L, "SUP-001", "改名", null, 0)).thenReturn(0);
    assertThatThrownBy(() -> supplierService.update(2L, "SUP-001", "改名", null, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
  }

  @Test
  @DisplayName("编辑：改码撞上已有编码 → 2007，且不调用 UPDATE")
  void updateRejectsCodeCollision() {
    when(supplierMapper.findById(2L)).thenReturn(Optional.of(supplier(2L, SupplierStatus.ENABLED, 1, null)));
    when(supplierMapper.countByCode("SUP-999")).thenReturn(1L);

    assertThatThrownBy(() -> supplierService.update(2L, "SUP-999", "示例供应商", null, 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SUPPLIER_CODE_EXISTS);
    verify(supplierMapper, never()).updateProfile(anyLong(), anyString(), anyString(), any(), anyInt());
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("编辑：编码未变时不查重——否则只改名称会被自己那条记录挡住")
  void updateSkipsDuplicateCheckWhenCodeUnchanged() {
    when(supplierMapper.findById(2L))
        .thenReturn(
            Optional.of(supplier(2L, SupplierStatus.ENABLED, 1, null)),
            Optional.of(supplier(2L, SupplierStatus.ENABLED, 2, "电子")));
    when(supplierMapper.updateProfile(2L, "SUP-001", "改名", "电子", 1)).thenReturn(1);

    SupplierSummary updated = supplierService.update(2L, "SUP-001", "改名", "电子", 1);

    verify(supplierMapper, never()).countByCode(anyString());
    assertThat(updated.version()).isEqualTo(2);
    assertThat(updated.category()).isEqualTo("电子");
  }

  @Test
  @DisplayName("编辑：审计只记真正变化的字段的前后值")
  void updateAuditsOnlyChangedFields() {
    when(supplierMapper.findById(2L))
        .thenReturn(
            Optional.of(supplier(2L, SupplierStatus.ENABLED, 1, null)),
            Optional.of(supplier(2L, SupplierStatus.ENABLED, 2, "电子")));
    when(supplierMapper.updateProfile(2L, "SUP-001", "示例供应商", "电子", 1)).thenReturn(1);

    supplierService.update(2L, "SUP-001", "示例供应商", "电子", 1);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.SUPPLIER_UPDATE), any(), eq(2L), detail.capture());
    assertThat(detail.getValue()).containsOnlyKeys("category");
  }

  @Test
  @DisplayName("分页：page 下界收敛为 1、size 上界收敛为 100，offset 按「page 从 1 开始」换算，条件原样透传")
  void pageClampsAndPassesConditions() {
    when(supplierMapper.findPage(anyString(), anyString(), anyLong(), anyInt())).thenReturn(List.of());
    when(supplierMapper.countBy(anyString(), anyString())).thenReturn(0L);

    PageResult<SupplierSummary> result = supplierService.page(0, 9999, "SUP", "电子");

    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(PageQuery.MAX_SIZE);
    verify(supplierMapper).findPage("SUP", "电子", 0L, PageQuery.MAX_SIZE);

    supplierService.page(3, 10, null, null);
    verify(supplierMapper).countBy(null, null);
    verify(supplierMapper).findPage(null, null, 20L, 10);
  }
}
