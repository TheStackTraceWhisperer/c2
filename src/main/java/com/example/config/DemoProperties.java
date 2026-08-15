package com.example.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Introspected
@ConfigurationProperties("com.example.demo")
public class DemoProperties {

  private GatewayProperties gateway = new GatewayProperties(8888, "localhost", 1);
  private EndpointProperties login = new EndpointProperties(9998, "localhost");
  private EndpointProperties lobby = new EndpointProperties(9999, "localhost");
  private EndpointProperties world = new EndpointProperties(9997, "localhost");

  @Getter
  @Setter
  @Introspected
  public static class EndpointProperties {
    private int port;
    private String hostname;

    public EndpointProperties() {}

    public EndpointProperties(int port, String hostname) {
      this.port = port;
      this.hostname = hostname;
    }
  }

  @Getter
  @Setter
  @Introspected
  public static class GatewayProperties extends EndpointProperties {
    private int nodeId = 1;

    public GatewayProperties() {}

    public GatewayProperties(int port, String hostname, int nodeId) {
      super(port, hostname);
      this.nodeId = nodeId;
    }
  }
}