package com.spe.context.masterdata.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.AccountType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.NormalBalance;
import com.spe.context.masterdata.api.TaxType;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务主数据：税码、会计科目、业务事件科目映射（docs/erp/34、48、52）。 */
@Service
@RequiredArgsConstructor
public class FinanceMasterDataService {

  private final TaxCodeMapper taxCodeMapper;
  private final GlAccountMapper glAccountMapper;
  private final AccountMappingMapper accountMappingMapper;
  private final CompanyMapper companyMapper;
  private final AuditFacade auditFacade;

  // ---------- tax code ----------

  @Transactional(readOnly = true)
  public PageResult<TaxCodeView> pageTaxCodes(int page, int size, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        taxCodeMapper.findPage(like, parsed, query.offset(), query.size()).stream()
            .map(TaxCodeView::of)
            .toList();
    return PageResult.of(rows, taxCodeMapper.countBy(like, parsed), query.page(), query.size());
  }

  @Transactional
  public TaxCodeView createTaxCode(
      String code, String country, BigDecimal rate, TaxType taxType, LocalDate effectiveFrom) {
    String normalizedCode = MasterDataSupport.trim(code);
    if (taxCodeMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.TAX_CODE_EXISTS);
    }
    TaxCode taxCode = new TaxCode();
    taxCode.setCode(normalizedCode);
    taxCode.setCountry(MasterDataSupport.upper(country));
    taxCode.setRate(rate);
    taxCode.setTaxType(taxType);
    taxCode.setEffectiveFrom(effectiveFrom);
    taxCode.setStatus(MasterDataStatus.ACTIVE);
    taxCode.setVersion(0);
    try {
      taxCodeMapper.insert(taxCode);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.TAX_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", taxCode.getCode());
    detail.put("country", taxCode.getCountry());
    detail.put("rate", rate);
    detail.put("taxType", taxType);
    auditFacade.record(AuditAction.TAX_CODE_CREATE, AuditTargetType.TAX_CODE, taxCode.getId(), detail);
    return TaxCodeView.of(taxCode);
  }

  @Transactional
  public TaxCodeView updateTaxCode(
      long id,
      String code,
      String country,
      BigDecimal rate,
      TaxType taxType,
      LocalDate effectiveFrom,
      int version) {
    TaxCode current = loadTaxCode(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode) && taxCodeMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.TAX_CODE_EXISTS);
    }
    String normalizedCountry = MasterDataSupport.upper(country);
    try {
      if (taxCodeMapper.updateProfile(
              id, normalizedCode, normalizedCountry, rate, taxType, effectiveFrom, version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.TAX_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "country", current.getCountry(), normalizedCountry);
    AuditDetails.putIfChanged(detail, "rate", current.getRate(), rate);
    AuditDetails.putIfChanged(detail, "taxType", current.getTaxType(), taxType);
    AuditDetails.putIfChanged(detail, "effectiveFrom", current.getEffectiveFrom(), effectiveFrom);
    auditFacade.record(AuditAction.TAX_CODE_UPDATE, AuditTargetType.TAX_CODE, id, detail);
    return new TaxCodeView(
        id, normalizedCode, normalizedCountry, rate, taxType, effectiveFrom, current.getStatus(), version + 1);
  }

  @Transactional
  public void changeTaxCodeStatus(long id, MasterDataStatus target, int version) {
    TaxCode current = loadTaxCode(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (taxCodeMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.TAX_CODE_STATUS_CHANGE,
        AuditTargetType.TAX_CODE,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  // ---------- gl account ----------

  @Transactional(readOnly = true)
  public PageResult<GlAccountView> pageGlAccounts(
      int page, int size, Long companyId, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        glAccountMapper.findPage(companyId, like, parsed, query.offset(), query.size()).stream()
            .map(GlAccountView::of)
            .toList();
    return PageResult.of(
        rows, glAccountMapper.countBy(companyId, like, parsed), query.page(), query.size());
  }

  @Transactional
  public GlAccountView createGlAccount(
      long companyId,
      String code,
      String name,
      AccountType accountType,
      NormalBalance normalBalance,
      Boolean requiresCostCenter) {
    requireActiveCompany(companyId);
    String normalizedCode = MasterDataSupport.trim(code);
    if (glAccountMapper.countByCompanyAndCode(companyId, normalizedCode) > 0) {
      throw new BizException(ErrorCode.GL_ACCOUNT_CODE_EXISTS);
    }
    GlAccount account = new GlAccount();
    account.setCompanyId(companyId);
    account.setCode(normalizedCode);
    account.setName(MasterDataSupport.trim(name));
    account.setAccountType(accountType);
    account.setNormalBalance(normalBalance);
    account.setRequiresCostCenter(Boolean.TRUE.equals(requiresCostCenter));
    account.setStatus(MasterDataStatus.ACTIVE);
    account.setVersion(0);
    try {
      glAccountMapper.insert(account);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.GL_ACCOUNT_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("companyId", companyId);
    detail.put("code", account.getCode());
    detail.put("name", account.getName());
    detail.put("accountType", accountType);
    auditFacade.record(
        AuditAction.GL_ACCOUNT_CREATE, AuditTargetType.GL_ACCOUNT, account.getId(), detail);
    return GlAccountView.of(account);
  }

  @Transactional
  public GlAccountView updateGlAccount(
      long id,
      String code,
      String name,
      AccountType accountType,
      NormalBalance normalBalance,
      Boolean requiresCostCenter,
      int version) {
    GlAccount current = loadGlAccount(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode)
        && glAccountMapper.countByCompanyAndCode(current.getCompanyId(), normalizedCode) > 0) {
      throw new BizException(ErrorCode.GL_ACCOUNT_CODE_EXISTS);
    }
    boolean costCenter = Boolean.TRUE.equals(requiresCostCenter);
    try {
      if (glAccountMapper.updateProfile(
              id,
              normalizedCode,
              MasterDataSupport.trim(name),
              accountType,
              normalBalance,
              costCenter,
              version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.GL_ACCOUNT_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "accountType", current.getAccountType(), accountType);
    AuditDetails.putIfChanged(detail, "normalBalance", current.getNormalBalance(), normalBalance);
    AuditDetails.putIfChanged(
        detail, "requiresCostCenter", current.getRequiresCostCenter(), costCenter);
    auditFacade.record(AuditAction.GL_ACCOUNT_UPDATE, AuditTargetType.GL_ACCOUNT, id, detail);
    return new GlAccountView(
        id,
        current.getCompanyId(),
        normalizedCode,
        MasterDataSupport.trim(name),
        accountType,
        normalBalance,
        costCenter,
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeGlAccountStatus(long id, MasterDataStatus target, int version) {
    GlAccount current = loadGlAccount(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (glAccountMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.GL_ACCOUNT_STATUS_CHANGE,
        AuditTargetType.GL_ACCOUNT,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  // ---------- account mapping ----------

  @Transactional(readOnly = true)
  public PageResult<AccountMappingView> pageMappings(
      int page, int size, Long companyId, String eventType, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String normalizedEvent = MasterDataSupport.upper(eventType);
    var rows =
        accountMappingMapper
            .findPage(companyId, normalizedEvent, parsed, query.offset(), query.size())
            .stream()
            .map(AccountMappingView::of)
            .toList();
    return PageResult.of(
        rows,
        accountMappingMapper.countBy(companyId, normalizedEvent, parsed),
        query.page(),
        query.size());
  }

  @Transactional
  public AccountMappingView createMapping(
      long companyId,
      String eventType,
      String dimension,
      long debitAccountId,
      long creditAccountId,
      Long taxAccountId,
      LocalDate validFrom) {
    requireActiveCompany(companyId);
    String normalizedEvent = MasterDataSupport.upper(eventType);
    String normalizedDimension = normalizeDimension(dimension);
    requireAccounts(companyId, debitAccountId, creditAccountId, taxAccountId);
    if (accountMappingMapper.countByKey(
            companyId, normalizedEvent, normalizedDimension, validFrom)
        > 0) {
      throw new BizException(ErrorCode.ACCOUNT_MAPPING_EXISTS);
    }
    AccountMapping mapping = new AccountMapping();
    mapping.setCompanyId(companyId);
    mapping.setEventType(normalizedEvent);
    mapping.setDimension(normalizedDimension);
    mapping.setDebitAccountId(debitAccountId);
    mapping.setCreditAccountId(creditAccountId);
    mapping.setTaxAccountId(taxAccountId);
    mapping.setValidFrom(validFrom);
    mapping.setStatus(MasterDataStatus.ACTIVE);
    mapping.setVersion(0);
    try {
      accountMappingMapper.insert(mapping);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.ACCOUNT_MAPPING_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("companyId", companyId);
    detail.put("eventType", normalizedEvent);
    detail.put("dimension", normalizedDimension);
    detail.put("debitAccountId", debitAccountId);
    detail.put("creditAccountId", creditAccountId);
    detail.put("validFrom", validFrom);
    auditFacade.record(
        AuditAction.ACCOUNT_MAPPING_CREATE, AuditTargetType.ACCOUNT_MAPPING, mapping.getId(), detail);
    return AccountMappingView.of(mapping);
  }

  @Transactional
  public AccountMappingView updateMapping(
      long id,
      String eventType,
      String dimension,
      long debitAccountId,
      long creditAccountId,
      Long taxAccountId,
      LocalDate validFrom,
      int version) {
    AccountMapping current = loadMapping(id);
    String normalizedEvent = MasterDataSupport.upper(eventType);
    String normalizedDimension = normalizeDimension(dimension);
    requireAccounts(current.getCompanyId(), debitAccountId, creditAccountId, taxAccountId);
    try {
      if (accountMappingMapper.updateProfile(
              id,
              normalizedEvent,
              normalizedDimension,
              debitAccountId,
              creditAccountId,
              taxAccountId,
              validFrom,
              version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.ACCOUNT_MAPPING_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "eventType", current.getEventType(), normalizedEvent);
    AuditDetails.putIfChanged(detail, "dimension", current.getDimension(), normalizedDimension);
    AuditDetails.putIfChanged(detail, "debitAccountId", current.getDebitAccountId(), debitAccountId);
    AuditDetails.putIfChanged(detail, "creditAccountId", current.getCreditAccountId(), creditAccountId);
    AuditDetails.putIfChanged(detail, "taxAccountId", current.getTaxAccountId(), taxAccountId);
    AuditDetails.putIfChanged(detail, "validFrom", current.getValidFrom(), validFrom);
    auditFacade.record(AuditAction.ACCOUNT_MAPPING_UPDATE, AuditTargetType.ACCOUNT_MAPPING, id, detail);
    return new AccountMappingView(
        id,
        current.getCompanyId(),
        normalizedEvent,
        normalizedDimension,
        debitAccountId,
        creditAccountId,
        taxAccountId,
        validFrom,
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeMappingStatus(long id, MasterDataStatus target, int version) {
    AccountMapping current = loadMapping(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (accountMappingMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.ACCOUNT_MAPPING_STATUS_CHANGE,
        AuditTargetType.ACCOUNT_MAPPING,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  private TaxCode loadTaxCode(long id) {
    return taxCodeMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.TAX_CODE_NOT_FOUND));
  }

  private GlAccount loadGlAccount(long id) {
    return glAccountMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.GL_ACCOUNT_NOT_FOUND));
  }

  private AccountMapping loadMapping(long id) {
    return accountMappingMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_MAPPING_NOT_FOUND));
  }

  private void requireActiveCompany(long companyId) {
    Company company =
        companyMapper
            .findById(companyId)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    if (company.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
  }

  private void requireAccounts(
      long companyId, long debitAccountId, long creditAccountId, Long taxAccountId) {
    requireAccount(companyId, debitAccountId);
    requireAccount(companyId, creditAccountId);
    if (taxAccountId != null) {
      requireAccount(companyId, taxAccountId);
    }
  }

  private void requireAccount(long companyId, long accountId) {
    GlAccount account =
        glAccountMapper
            .findByIdAndCompany(accountId, companyId)
            .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_MAPPING_INVALID, "科目不属于该公司"));
    if (account.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.ACCOUNT_MAPPING_INVALID, "科目已停用");
    }
  }

  private static String normalizeDimension(String dimension) {
    String trimmed = MasterDataSupport.trim(dimension);
    return trimmed == null ? "" : trimmed;
  }
}
