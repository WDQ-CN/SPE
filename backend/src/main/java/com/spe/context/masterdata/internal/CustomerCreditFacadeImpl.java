package com.spe.context.masterdata.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CustomerCreditFacade;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 客户信用占用：发票增加、收款减少；增加使用行级守卫。 */
@Component
@RequiredArgsConstructor
public class CustomerCreditFacadeImpl implements CustomerCreditFacade {

  private final CustomerMapper customerMapper;

  @Override
  @Transactional
  public void increaseUsed(long customerId, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      return;
    }
    if (customerMapper.increaseCreditUsed(customerId, amount) == 0) {
      throw new BizException(ErrorCode.SALES_CREDIT_EXCEEDED);
    }
  }

  @Override
  @Transactional
  public void decreaseUsed(long customerId, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      return;
    }
    if (customerMapper.decreaseCreditUsed(customerId, amount) == 0) {
      throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
    }
  }
}
