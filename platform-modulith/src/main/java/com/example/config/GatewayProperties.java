package com.example.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Introspected
@ConfigurationProperties("app.gateway")
public class GatewayProperties {

  @NotBlank
  private String host;

  @Min(1)
  private int port;

  @Min(0)
  private int nodeId;

  @Min(1)
  private long rateCapacity;

  @Min(1)
  private long rateRefill;
}