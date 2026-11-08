package com.spe.context.finance.internal;

import com.spe.context.asset.api.events.AssetCapitalizedEvent;
import com.spe.context.asset.api.events.AssetDepreciatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 资产事件 → 财务过账（同一事务；映射缺失回滚资本化/折旧）。 */
@Component
@RequiredArgsConstructor
public class AssetPostingListener {

  private final JournalPostingService postingService;

  @EventListener
  public void onCapitalized(AssetCapitalizedEvent event) {
    postingService.postAssetCapitalization(event);
  }

  @EventListener
  public void onDepreciated(AssetDepreciatedEvent event) {
    postingService.postAssetDepreciation(event);
  }
}
