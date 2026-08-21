package com.example.gateway;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

@Singleton
@Requires(env = "gateway")
public final class GatewayHealthIndicator implements HealthIndicator {

  private final QuicGateway gateway;

  public GatewayHealthIndicator(QuicGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public Publisher<HealthResult> getResult() {
    HealthStatus status = gateway.isRunning() ? HealthStatus.UP : HealthStatus.DOWN;
    return Publishers.just(HealthResult.builder("gateway", status).build());
  }
}