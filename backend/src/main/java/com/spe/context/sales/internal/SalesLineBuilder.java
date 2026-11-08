package com.spe.context.sales.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 销售行校验与金额计算，保证报价/订单同一口径。 */
@Component
@RequiredArgsConstructor
class SalesLineBuilder {

  private final MasterDataQueryFacade masterDataQueryFacade;

  Result build(List<SalesLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.SALES_ORDER_LINE_INVALID, "至少需要一行");
    }
    List<SalesSupport.CalculatedLine> lines = new ArrayList<>(commands.size());
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal tax = BigDecimal.ZERO;
    for (SalesLineCommand command : commands) {
      ItemBrief item =
          masterDataQueryFacade
              .findItem(command.itemId())
              .filter(ItemBrief::active)
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      SalesSupport.CalculatedLine line = SalesSupport.calculate(command, item);
      lines.add(line);
      net = net.add(line.netAmount());
      tax = tax.add(line.taxAmount());
    }
    return new Result(lines, SalesSupport.money(net), SalesSupport.money(tax), SalesSupport.money(net.add(tax)));
  }

  record Result(
      List<SalesSupport.CalculatedLine> lines,
      BigDecimal netAmount,
      BigDecimal taxAmount,
      BigDecimal totalAmount) {}
}
