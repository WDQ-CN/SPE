package com.spe.context.procurement.internal;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 门户出价频控配置（application.yml 的 app.portal-bid-limiter）。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.portal-bid-limiter")
public class PortalBidLimiterProperties {

  private int maxPerWindow = 20;
  private Duration window = Duration.ofSeconds(10);
  private int maxEntries = 100000;
}
