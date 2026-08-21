package com.example.operator;

import com.example.server.entity.RealmRecord;
import com.example.server.entity.ZoneTopologyRecord;
import com.example.server.repository.RealmJdbcRepository;
import com.example.server.repository.ZoneTopologyJdbcRepository;
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
  private final RealmJdbcRepository realmRepository;
  private final ZoneTopologyJdbcRepository zoneRepository;

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
    log.info("[OPERATOR] MMO Database-Driven Fleet Operator initialized in namespace '{}' using image '{}'",
      namespace, getImage());
    reconcile();
  }

  @Scheduled(fixedDelay = "2s", initialDelay = "1s")
  public void reconcile() {
    try {
      reconcileRealms();
      reconcileZones();
    } catch (DataAccessException dae) {
      log.warn("[OPERATOR] Database schema not ready yet: {}", dae.getMessage());
    } catch (Exception e) {
      log.error("[OPERATOR] Reconciliation loop error: {}", e.getMessage(), e);
    }
  }

  private void reconcileRealms() {
    Iterable<RealmRecord> desiredRealms = realmRepository.findAll();

    Map<Integer, Pod> realmPods = k8s.pods().inNamespace(namespace)
      .withLabel("app.mmo/component", "world")
      .list().getItems().stream()
      .filter(p -> p.getMetadata() != null && p.getMetadata().getLabels() != null && p.getMetadata().getLabels().containsKey("app.mmo/realm-id"))
      .collect(Collectors.toMap(
        p -> Integer.parseInt(p.getMetadata().getLabels().get("app.mmo/realm-id")),
        p -> p,
        (existing, duplicate) -> existing
      ));

    for (RealmRecord realm : desiredRealms) {
      Pod pod = realmPods.get(realm.id());
      if (pod == null) {
        log.info("[OPERATOR] Provisioning missing World Pod for Realm #{} ({})", realm.id(), realm.name());
        spawnRealmPod(realm);
      } else {
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
        String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

        if ("Running".equals(phase) && podIp != null && !podIp.isBlank()) {
          if (!podIp.equals(realm.host()) || !"ONLINE".equals(realm.status())) {
            log.info("[OPERATOR] Realm #{} is RUNNING. Syncing host={}:{} to DB", realm.id(), podIp, realm.port());
            realmRepository.updateEndpoint(realm.id(), podIp, realm.port(), "ONLINE");
          }
        }
      }
    }
  }

  private void reconcileZones() {
    Iterable<ZoneTopologyRecord> desiredZones = zoneRepository.findAll();

    Map<Integer, Pod> zonePods = k8s.pods().inNamespace(namespace)
      .withLabel("app.mmo/component", "zone")
      .list().getItems().stream()
      .filter(p -> p.getMetadata() != null && p.getMetadata().getLabels() != null && p.getMetadata().getLabels().containsKey("app.mmo/zone-id"))
      .collect(Collectors.toMap(
        p -> Integer.parseInt(p.getMetadata().getLabels().get("app.mmo/zone-id")),
        p -> p,
        (existing, duplicate) -> existing
      ));

    for (ZoneTopologyRecord zone : desiredZones) {
      Pod pod = zonePods.get(zone.id());
      if (pod == null) {
        log.info("[OPERATOR] Provisioning missing Zone Pod for Zone #{} ({}, Realm #{})", zone.id(), zone.name(), zone.realmId());
        spawnZonePod(zone);
      } else {
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
        String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

        if ("Running".equals(phase) && podIp != null && !podIp.isBlank()) {
          if (!podIp.equals(zone.host()) || !"ONLINE".equals(zone.status())) {
            log.info("[OPERATOR] Zone #{} is RUNNING. Syncing host={}:{} to DB", zone.id(), podIp, zone.port());
            zoneRepository.updateEndpoint(zone.id(), podIp, zone.port(), "ONLINE");
          }
        }
      }
    }
  }

  private void spawnRealmPod(RealmRecord realm) {
    Container container = new ContainerBuilder()
      .withName("world")
      .withImage(getImage())
      .withImagePullPolicy("Never")
      .addNewEnv().withName("MICRONAUT_ENVIRONMENTS").withValue("world").endEnv()
      .addNewEnv().withName("FLYWAY_DATASOURCES_DEFAULT_ENABLED").withValue("false").endEnv()
      .addNewEnv().withName("APP_WORLD_HOST").withValue("0.0.0.0").endEnv()
      .addNewEnv().withName("APP_WORLD_PORT").withValue(String.valueOf(realm.port())).endEnv()
      .addNewEnv().withName("APP_WORLD_REALM_ID").withValue(String.valueOf(realm.id())).endEnv()
      .addNewPort().withContainerPort(realm.port()).withProtocol("UDP").withName("quic").endPort()
      .addNewEnvFrom()
      .withNewConfigMapRef()
      .withName("platform-common-config")
      .endConfigMapRef()
      .endEnvFrom()
      .build();

    Pod pod = new PodBuilder()
      .withNewMetadata()
      .withName("world-" + realm.id())
      .withNamespace(namespace)
      .addToLabels("app.mmo/component", "world")
      .addToLabels("app.mmo/realm-id", String.valueOf(realm.id()))
      .endMetadata()
      .withNewSpec()
      .withContainers(container)
      .withRestartPolicy("Always")
      .endSpec()
      .build();

    k8s.pods().inNamespace(namespace).resource(pod).create();
  }

  private void spawnZonePod(ZoneTopologyRecord zone) {
    Container container = new ContainerBuilder()
      .withName("zone")
      .withImage(getImage())
      .withImagePullPolicy("Never")
      .addNewEnv().withName("MICRONAUT_ENVIRONMENTS").withValue("zone").endEnv()
      .addNewEnv().withName("FLYWAY_DATASOURCES_DEFAULT_ENABLED").withValue("false").endEnv()
      .addNewEnv().withName("APP_ZONE_HOST").withValue("0.0.0.0").endEnv()
      .addNewEnv().withName("APP_ZONE_PORT").withValue(String.valueOf(zone.port())).endEnv()
      .addNewEnv().withName("APP_ZONE_ZONE_ID").withValue(String.valueOf(zone.id())).endEnv()
      .addNewPort().withContainerPort(zone.port()).withProtocol("UDP").withName("quic").endPort()
      .addNewEnvFrom()
      .withNewConfigMapRef()
      .withName("platform-common-config")
      .endConfigMapRef()
      .endEnvFrom()
      .build();

    Pod pod = new PodBuilder()
      .withNewMetadata()
      .withName("zone-" + zone.id())
      .withNamespace(namespace)
      .addToLabels("app.mmo/component", "zone")
      .addToLabels("app.mmo/zone-id", String.valueOf(zone.id()))
      .addToLabels("app.mmo/realm-id", String.valueOf(zone.realmId()))
      .endMetadata()
      .withNewSpec()
      .withContainers(container)
      .withRestartPolicy("Always")
      .endSpec()
      .build();

    k8s.pods().inNamespace(namespace).resource(pod).create();
  }
}