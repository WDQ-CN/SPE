package com.spe.context.masterdata.api;

import java.math.BigDecimal;

/** 客户信用占用写门面（销售开票/收款用）；内部用行级守卫防止并发超额。 */
public interface CustomerCreditFacade {

  void increaseUsed(long customerId, BigDecimal amount);

  void decreaseUsed(long customerId, BigDecimal amount);
}
