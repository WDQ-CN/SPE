package com.spe.common.fixture;

import com.spe.common.event.DomainEvent;

/**
 * 故意违规的测试夹具：领域事件载荷存在非 final 字段（违反 R-D 的不可变要求）。
 * 见 {@link com.spe.architecture.ArchUnitRulesAreEffectiveTest}。
 */
public class MutableEvent implements DomainEvent {

  private String supplierCode;

  public String supplierCode() {
    return supplierCode;
  }
}
