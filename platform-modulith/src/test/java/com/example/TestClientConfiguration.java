package com.example;

import com.example.client.ClientConfiguration;
import com.example.config.DemoProperties;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

@Singleton
@Replaces(ClientConfiguration.class)
public class TestClientConfiguration implements ClientConfiguration {

  private final DemoProperties demoProperties;

  public TestClientConfiguration(DemoProperties demoProperties) {
    this.demoProperties = demoProperties;
  }

  @Override
  public GatewayEndpoint getGateway() {
    DemoProperties.GatewayProperties gateway = demoProperties.getGateway();
    return new GatewayEndpoint() {
      @Override
      public int getPort() {
        return gateway.getPort();
      }

      @Override
      public String getHostname() {
        return gateway.getHostname();
      }
    };
  }
}