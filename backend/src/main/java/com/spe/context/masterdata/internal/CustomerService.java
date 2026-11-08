package com.spe.context.masterdata.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 客户主数据（docs/erp/31、48、52）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

  private final CustomerMapper customerMapper;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public PageResult<CustomerView> page(int page, int size, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        customerMapper.findPage(like, parsed, query.offset(), query.size()).stream()
            .map(CustomerView::of)
            .toList();
    return PageResult.of(rows, customerMapper.countBy(like, parsed), query.page(), query.size());
  }

  @Transactional
  public CustomerView create(
      String code,
      String name,
      String taxNo,
      String address,
      String contactName,
      String contactPhone,
      String currency,
      String paymentTerms,
      BigDecimal creditLimit) {
    String normalizedCode = MasterDataSupport.trim(code);
    if (customerMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.CUSTOMER_CODE_EXISTS);
    }
    Customer customer = new Customer();
    customer.setCode(normalizedCode);
    customer.setName(MasterDataSupport.trim(name));
    customer.setTaxNo(MasterDataSupport.trim(taxNo));
    customer.setAddress(MasterDataSupport.trim(address));
    customer.setContactName(MasterDataSupport.trim(contactName));
    customer.setContactPhone(MasterDataSupport.trim(contactPhone));
    customer.setCurrency(MasterDataSupport.upper(currency));
    customer.setPaymentTerms(MasterDataSupport.trim(paymentTerms));
    customer.setCreditLimit(creditLimit == null ? BigDecimal.ZERO : creditLimit);
    customer.setCreditUsed(BigDecimal.ZERO);
    customer.setStatus(MasterDataStatus.ACTIVE);
    customer.setVersion(0);
    try {
      customerMapper.insert(customer);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.CUSTOMER_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", customer.getCode());
    detail.put("name", customer.getName());
    detail.put("currency", customer.getCurrency());
    detail.put("creditLimit", customer.getCreditLimit());
    auditFacade.record(AuditAction.CUSTOMER_CREATE, AuditTargetType.CUSTOMER, customer.getId(), detail);
    log.info("创建客户 {}（id={}）", normalizedCode, customer.getId());
    return CustomerView.of(customer);
  }

  @Transactional
  public CustomerView update(
      long id,
      String code,
      String name,
      String taxNo,
      String address,
      String contactName,
      String contactPhone,
      String currency,
      String paymentTerms,
      BigDecimal creditLimit,
      int version) {
    Customer current = load(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode) && customerMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.CUSTOMER_CODE_EXISTS);
    }
    BigDecimal normalizedLimit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
    try {
      if (customerMapper.updateProfile(
              id,
              normalizedCode,
              MasterDataSupport.trim(name),
              MasterDataSupport.taxNoForUpdate(taxNo, current.getTaxNo()),
              MasterDataSupport.trim(address),
              MasterDataSupport.trim(contactName),
              MasterDataSupport.trim(contactPhone),
              MasterDataSupport.upper(currency),
              MasterDataSupport.trim(paymentTerms),
              normalizedLimit,
              version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.CUSTOMER_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "address", current.getAddress(), address);
    AuditDetails.putIfChanged(detail, "contactName", current.getContactName(), contactName);
    AuditDetails.putIfChanged(detail, "contactPhone", current.getContactPhone(), contactPhone);
    AuditDetails.putIfChanged(detail, "currency", current.getCurrency(), currency);
    AuditDetails.putIfChanged(detail, "paymentTerms", current.getPaymentTerms(), paymentTerms);
    AuditDetails.putIfChanged(detail, "creditLimit", current.getCreditLimit(), normalizedLimit);
    if (!Objects.equals(current.getTaxNo(), MasterDataSupport.trim(taxNo))) {
      detail.put("taxNoChanged", true);
    }
    auditFacade.record(AuditAction.CUSTOMER_UPDATE, AuditTargetType.CUSTOMER, id, detail);
    return new CustomerView(
        id,
        normalizedCode,
        MasterDataSupport.trim(name),
        CustomerView.mask(MasterDataSupport.trim(taxNo)),
        MasterDataSupport.trim(address),
        MasterDataSupport.trim(contactName),
        MasterDataSupport.trim(contactPhone),
        MasterDataSupport.upper(currency),
        MasterDataSupport.trim(paymentTerms),
        normalizedLimit,
        current.getCreditUsed(),
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeStatus(long id, MasterDataStatus target, int version) {
    Customer current = load(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (customerMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.CUSTOMER_STATUS_CHANGE,
        AuditTargetType.CUSTOMER,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  private Customer load(long id) {
    return customerMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_NOT_FOUND));
  }
}
