package com.example.client;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Introspected
@ConfigurationProperties("client")
public class DefaultClientConfiguration implements ClientConfiguration {

  private GatewayProperties gateway = new GatewayProperties();

  @Getter
  @Setter
  @Introspected
  public static class GatewayProperties implements GatewayEndpoint {
    private int port = 8888;
    private String hostname = "localhost";
  }
}