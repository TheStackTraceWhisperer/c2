package com.example;

import com.example.client.ClientConfiguration;
import com.example.config.GatewayProperties;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@Replaces(ClientConfiguration.class)
@RequiredArgsConstructor
public class TestClientConfiguration implements ClientConfiguration {

  private final GatewayProperties gatewayProperties;

  @Override
  public GatewayEndpoint getGateway() {
    return new GatewayEndpoint() {
      @Override
      public int getPort() {
        return gatewayProperties.getPort();
      }

      @Override
      public String getHostname() {
        return gatewayProperties.getHost();
      }
    };
  }
}