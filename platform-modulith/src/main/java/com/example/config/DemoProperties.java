package com.example.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties("com.example.demo")
public class DemoProperties {

  private GatewayProperties gateway;
  private LoginProperties login;
  private LobbyProperties lobby;
  private WorldProperties world;
  private ZoneProperties zone;

  @Getter
  @Setter
  @Serdeable
  @NoArgsConstructor
  @AllArgsConstructor
  public static class EndpointProperties {
    private int port;
    private String hostname;
  }

  @Getter
  @Setter
  @ConfigurationProperties("gateway")
  public static class GatewayProperties extends EndpointProperties {

    private int nodeId;
    private RateLimitProperties rateLimit;

    @Getter
    @Setter
    @ConfigurationProperties("rate-limit")
    public static class RateLimitProperties {
      private long capacity;
      private long refillRate;
    }
  }

  @Getter
  @Setter
  @ConfigurationProperties("login")
  public static class LoginProperties extends EndpointProperties {}

  @Getter
  @Setter
  @ConfigurationProperties("lobby")
  public static class LobbyProperties extends EndpointProperties {}

  @Getter
  @Setter
  @ConfigurationProperties("world")
  public static class WorldProperties extends EndpointProperties {}

  @Getter
  @Setter
  @ConfigurationProperties("zone")
  public static class ZoneProperties extends EndpointProperties {}
}