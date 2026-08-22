package com.example.operator;

import com.example.server.entity.WorldRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.repository.WorldJdbcRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Singleton
@Context
@Requires(env = "operator")
@RequiredArgsConstructor
public class MmoFleetOperator {

  private final KubernetesClient k8s;
  private final WorldJdbcRepository worldRepository;
  private final ZoneInstanceJdbcRepository zoneInstanceRepository;

  @Value("${app.operator.namespace:default}")
  private String namespace;

  @Nullable
  @Value("${app.operator.image}")
  private String configuredImage;

  private String getImage() {
    return (configuredImage != null && !configuredImage.isBlank())
      ? configuredImage
      : "platform-modulith:latest";
  }

  @PostConstruct
  public void init() {
    log.info("[OPERATOR] MMO Fleet Operator initialized in namespace '{}' using image '{}'",
      namespace, getImage());
    reconcile();
  }

  @Scheduled(fixedDelay = "2s", initialDelay = "1s")
  public void reconcile() {
    try {
      reconcileWorlds();
      reconcileZones();
    } catch (DataAccessException dae) {
      log.warn("[OPERATOR] Database schema not ready yet: {}", dae.getMessage());
    } catch (Exception e) {
      log.error("[OPERATOR] Reconciliation loop error: {}", e.getMessage(), e);
    }
  }

  private void reconcileWorlds() {
    Iterable<WorldRecord> desiredWorlds = worldRepository.findAll();

    Map<Integer, Pod> worldPods = k8s.pods().inNamespace(namespace)
      .withLabel("app.mmo/component", "world")
      .list().getItems().stream()
      .filter(p -> p.getMetadata() != null && p.getMetadata().getLabels() != null && p.getMetadata().getLabels().containsKey("app.mmo/world-id"))
      .collect(Collectors.toMap(
        p -> Integer.parseInt(p.getMetadata().getLabels().get("app.mmo/world-id")),
        p -> p,
        (existing, duplicate) -> existing
      ));

    for (WorldRecord world : desiredWorlds) {
      Pod pod = worldPods.get(world.id());
      if (pod == null) {
        log.info("[OPERATOR] Provisioning missing World Pod for World #{} ({})", world.id(), world.name());
        spawnWorldPod(world);
      } else {
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
        String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

        if ("Running".equals(phase) && podIp != null && !podIp.isBlank()) {
          if (!podIp.equals(world.host()) || !"ONLINE".equals(world.status())) {
            log.info("[OPERATOR] World #{} is RUNNING. Syncing host={}:{} to DB", world.id(), podIp, world.port());
            worldRepository.updateEndpoint(world.id(), podIp, world.port(), "ONLINE");
          }
        }
      }
    }
  }

  private void reconcileZones() {
    Iterable<ZoneInstanceRecord> desiredZones = zoneInstanceRepository.findAll();

    Map<Integer, Pod> zonePods = k8s.pods().inNamespace(namespace)
      .withLabel("app.mmo/component", "zone")
      .list().getItems().stream()
      .filter(p -> p.getMetadata() != null && p.getMetadata().getLabels() != null && p.getMetadata().getLabels().containsKey("app.mmo/zone-instance-id"))
      .collect(Collectors.toMap(
        p -> Integer.parseInt(p.getMetadata().getLabels().get("app.mmo/zone-instance-id")),
        p -> p,
        (existing, duplicate) -> existing
      ));

    for (ZoneInstanceRecord instance : desiredZones) {
      Pod pod = zonePods.get(instance.id());
      if (pod == null) {
        log.info("[OPERATOR] Provisioning missing Zone Pod for Instance #{} (Zone #{}, World #{})",
          instance.id(), instance.zoneId(), instance.worldId());
        spawnZonePod(instance);
      } else {
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
        String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

        if ("Running".equals(phase) && podIp != null && !podIp.isBlank()) {
          if (!podIp.equals(instance.host()) || !"ONLINE".equals(instance.status())) {
            log.info("[OPERATOR] Zone Instance #{} is RUNNING. Syncing host={}:{} to DB", instance.id(), podIp, instance.port());
            zoneInstanceRepository.updateEndpoint(instance.id(), podIp, instance.port(), "ONLINE");
          }
        }
      }
    }
  }

  private void spawnWorldPod(WorldRecord world) {
    Container container = new ContainerBuilder()
      .withName("world")
      .withImage(getImage())
      .withImagePullPolicy("Never")
      .addNewEnv().withName("MICRONAUT_ENVIRONMENTS").withValue("world").endEnv()
      .addNewEnv().withName("FLYWAY_DATASOURCES_DEFAULT_ENABLED").withValue("false").endEnv()
      .addNewEnv().withName("APP_WORLD_HOST").withValue("0.0.0.0").endEnv()
      .addNewEnv().withName("APP_WORLD_PORT").withValue(String.valueOf(world.port())).endEnv()
      .addNewEnv().withName("APP_WORLD_REALM_ID").withValue(String.valueOf(world.id())).endEnv()
      .addNewPort().withContainerPort(world.port()).withProtocol("UDP").withName("quic").endPort()
      .addNewEnvFrom()
      .withNewConfigMapRef()
      .withName("platform-common-config")
      .endConfigMapRef()
      .endEnvFrom()
      .build();

    Pod pod = new PodBuilder()
      .withNewMetadata()
      .withName("world-" + world.id())
      .withNamespace(namespace)
      .addToLabels("app.mmo/component", "world")
      .addToLabels("app.mmo/world-id", String.valueOf(world.id()))
      .endMetadata()
      .withNewSpec()
      .withContainers(container)
      .withRestartPolicy("Always")
      .endSpec()
      .build();

    k8s.pods().inNamespace(namespace).resource(pod).create();
  }

  private void spawnZonePod(ZoneInstanceRecord instance) {
    Container container = new ContainerBuilder()
      .withName("zone")
      .withImage(getImage())
      .withImagePullPolicy("Never")
      .addNewEnv().withName("MICRONAUT_ENVIRONMENTS").withValue("zone").endEnv()
      .addNewEnv().withName("FLYWAY_DATASOURCES_DEFAULT_ENABLED").withValue("false").endEnv()
      .addNewEnv().withName("APP_ZONE_HOST").withValue("0.0.0.0").endEnv()
      .addNewEnv().withName("APP_ZONE_PORT").withValue(String.valueOf(instance.port())).endEnv()
      .addNewEnv().withName("APP_ZONE_ZONE_ID").withValue(String.valueOf(instance.zoneId())).endEnv()
      .addNewPort().withContainerPort(instance.port()).withProtocol("UDP").withName("quic").endPort()
      .addNewEnvFrom()
      .withNewConfigMapRef()
      .withName("platform-common-config")
      .endConfigMapRef()
      .endEnvFrom()
      .build();

    Pod pod = new PodBuilder()
      .withNewMetadata()
      .withName("zone-" + instance.id())
      .withNamespace(namespace)
      .addToLabels("app.mmo/component", "zone")
      .addToLabels("app.mmo/zone-instance-id", String.valueOf(instance.id()))
      .addToLabels("app.mmo/world-id", String.valueOf(instance.worldId()))
      .endMetadata()
      .withNewSpec()
      .withContainers(container)
      .withRestartPolicy("Always")
      .endSpec()
      .build();

    k8s.pods().inNamespace(namespace).resource(pod).create();
  }
}