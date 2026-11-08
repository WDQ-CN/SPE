package com.spe.context.integration.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.integration.internal.EventSubscriptionService;
import com.spe.context.integration.internal.EventSubscriptionView;
import com.spe.context.integration.internal.OutboxEvent;
import com.spe.context.integration.internal.OutboxService;
import com.spe.context.integration.internal.WebhookDelivery;
import com.spe.context.integration.internal.WebhookDispatcher;
import com.spe.context.integration.internal.web.dto.CreateSubscriptionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 集成订阅/outbox（docs/erp/40）。 */
@RestController
@RequestMapping("/api/integrations")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class IntegrationController {

  private final EventSubscriptionService subscriptionService;
  private final OutboxService outboxService;
  private final WebhookDispatcher dispatcher;

  @GetMapping("/subscriptions")
  public Result<PageResult<EventSubscriptionView>> subscriptions(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam long companyId) {
    return Result.ok(subscriptionService.page(page, size, companyId));
  }

  @PostMapping("/subscriptions")
  public Result<EventSubscriptionService.Created> create(
      @Valid @RequestBody CreateSubscriptionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        subscriptionService.create(
            operator.uid(),
            request.companyId(),
            request.url(),
            request.eventTypes(),
            request.secret()));
  }

  @PostMapping("/subscriptions/{id}/enable")
  public Result<EventSubscriptionView> enable(@PathVariable long id) {
    return Result.ok(subscriptionService.changeStatus(id, "ACTIVE"));
  }

  @PostMapping("/subscriptions/{id}/disable")
  public Result<EventSubscriptionView> disable(@PathVariable long id) {
    return Result.ok(subscriptionService.changeStatus(id, "DISABLED"));
  }

  @GetMapping("/outbox")
  public Result<PageResult<OutboxEvent>> outbox(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(outboxService.page(page, size, status));
  }

  @PostMapping("/outbox/dispatch")
  public Result<Integer> dispatch() {
    return Result.ok(dispatcher.dispatch());
  }

  @GetMapping("/outbox/{id}/deliveries")
  public Result<List<WebhookDelivery>> deliveries(@PathVariable long id) {
    return Result.ok(outboxService.deliveries(id));
  }
}
