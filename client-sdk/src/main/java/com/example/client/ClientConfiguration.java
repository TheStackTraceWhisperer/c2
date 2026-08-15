package com.example.client;

public interface ClientConfiguration {

  GatewayEndpoint getGateway();

  interface GatewayEndpoint {
    int getPort();

    String getHostname();
  }
}
