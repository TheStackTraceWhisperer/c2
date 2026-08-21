package com.example.operator;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Factory
@Requires(env = "operator")
public class KubernetesClientFactory {

  @Singleton
  public KubernetesClient kubernetesClient() {
    Config config = new ConfigBuilder().build();
    return new KubernetesClientBuilder().withConfig(config).build();
  }
}