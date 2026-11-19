package com.example.operator;

import com.example.server.entity.WorldRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.repository.WorldJdbcRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Singleton
@Context
@Requires(env = "operator")
@RequiredArgsConstructor
public class MmoFleetOperator {

  private static final String DEFAULT_NAMESPACE = "default";
  private static final String DEFAULT_IMAGE = "platform-modulith:latest";
  private static final String DEFAULT_IMAGE_PULL_POLICY = "Never";

  private final KubernetesClient k8s;
  private final WorldJdbcRepository worldRepository;
  private final ZoneInstanceJdbcRepository zoneInstanceRepository;

  @Value("${app.operator.namespace:default}")
  private String namespace = DEFAULT_NAMESPACE;

  @Nullable
  @Value("${app.operator.image}")
  private String configuredImage;

  @Value("${app.operator.image-pull-policy:Never}")
  private String imagePullPolicy = DEFAULT_IMAGE_PULL_POLICY;

  private String getNamespace() {
    return (namespace != null && !namespace.isBlank()) ? namespace : DEFAULT_NAMESPACE;
  }

  private String getImage() {
    return (configuredImage != null && !configuredImage.isBlank())
      ? configuredImage
      : DEFAULT_IMAGE;
  }

  private String getImagePullPolicy() {
    return (imagePullPolicy != null && !imagePullPolicy.isBlank())
      ? imagePullPolicy
      : DEFAULT_IMAGE_PULL_POLICY;
  }

  void overrideConfigForTest(String namespace, String configuredImage, String imagePullPolicy) {
    this.namespace = namespace;
    this.configuredImage = configuredImage;
    this.imagePullPolicy = imagePullPolicy;
  }

  @PostConstruct
  public void init() {
    log.info("[OPERATOR] MMO Fleet Operator initialized in namespace '{}' using image '{}' (pullPolicy={})",
      getNamespace(), getImage(), getImagePullPolicy());
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
    Map<Integer, Pod> worldPods = indexPodsByIntLabel("world", "app.mmo/world-id");

    for (WorldRecord world : desiredWorlds) {
      Pod pod = worldPods.get(world.id());
      String labelDesc = "World #" + world.id() + " (" + world.name() + ")";
      reconcileEntityPod(
        world.id(),
        labelDesc,
        world.host(),
        world.status(),
        world.port(),
        pod,
        () -> spawnWorldPod(world),
        worldRepository::updateEndpoint
      );
    }
  }

  private void reconcileZones() {
    Iterable<ZoneInstanceRecord> desiredZones = zoneInstanceRepository.findAll();
    Map<Integer, Pod> zonePods = indexPodsByIntLabel("zone", "app.mmo/instance-id");

    for (ZoneInstanceRecord instance : desiredZones) {
      Pod pod = zonePods.get(instance.id());
      String labelDesc = "Zone Instance #" + instance.id()
        + " (World #" + instance.worldId()
        + ", Zone #" + instance.zoneId()
        + ", Tag '" + instance.instanceTag() + "')";
      reconcileEntityPod(
        instance.id(),
        labelDesc,
        instance.host(),
        instance.status(),
        instance.port(),
        pod,
        () -> spawnZonePod(instance),
        zoneInstanceRepository::updateEndpoint
      );
    }
  }

  private Map<Integer, Pod> indexPodsByIntLabel(String component, String labelKey) {
    Map<Integer, Pod> indexed = new HashMap<>();
    List<Pod> pods = k8s.pods().inNamespace(getNamespace())
      .withLabel("app.mmo/component", component)
      .list()
      .getItems();

    if (pods == null) {
      return indexed;
    }

    for (Pod pod : pods) {
      if (pod == null || pod.getMetadata() == null || pod.getMetadata().getLabels() == null) {
        continue;
      }
      String rawId = pod.getMetadata().getLabels().get(labelKey);
      if (rawId == null) {
        continue;
      }
      try {
        int id = Integer.parseInt(rawId);
        indexed.putIfAbsent(id, pod);
      } catch (NumberFormatException nfe) {
        log.warn("[OPERATOR] Ignoring {} pod '{}' with non-numeric label {}='{}'",
          component, pod.getMetadata().getName(), labelKey, rawId);
      }
    }
    return indexed;
  }

  @FunctionalInterface
  private interface EndpointUpdater {
    void update(int id, String host, int port, String status);
  }

  private void reconcileEntityPod(
      int entityId,
      String entityLabel,
      String currentHost,
      String currentStatus,
      int port,
      Pod pod,
      Runnable spawnAction,
      EndpointUpdater updater) {
    if (pod == null) {
      if ("ONLINE".equals(currentStatus)) {
        log.warn("[OPERATOR] {} pod missing while DB status was ONLINE; marking OFFLINE", entityLabel);
        updater.update(entityId, "", port, "OFFLINE");
      }
      log.info("[OPERATOR] Provisioning missing Pod for {}", entityLabel);
      try {
        spawnAction.run();
      } catch (KubernetesClientException e) {
        log.warn("[OPERATOR] Failed to spawn pod for {}: {}", entityLabel, e.getMessage());
      }
      return;
    }

    boolean terminating = pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null;
    if (terminating) {
      if ("ONLINE".equals(currentStatus)) {
        log.warn("[OPERATOR] {} pod is terminating; marking OFFLINE", entityLabel);
        updater.update(entityId, "", port, "OFFLINE");
      }
      return;
    }

    String phase = (pod.getStatus() != null && pod.getStatus().getPhase() != null)
      ? pod.getStatus().getPhase()
      : "Unknown";
    String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

    if ("Running".equals(phase) && podIp != null && !podIp.isBlank()) {
      if (!podIp.equals(currentHost) || !"ONLINE".equals(currentStatus)) {
        log.info("[OPERATOR] {} is RUNNING. Syncing host={}:{} to DB", entityLabel, podIp, port);
        updater.update(entityId, podIp, port, "ONLINE");
      }
    } else if ("Failed".equals(phase)) {
      if ("ONLINE".equals(currentStatus)) {
        log.warn("[OPERATOR] {} pod is Failed while DB status was ONLINE; marking OFFLINE", entityLabel);
        updater.update(entityId, "", port, "OFFLINE");
      }
      String podName = pod.getMetadata() != null ? pod.getMetadata().getName() : "<unnamed>";
      log.warn("[OPERATOR] Deleting Failed pod '{}' for {} and recreating", podName, entityLabel);
      try {
        k8s.pods().inNamespace(getNamespace()).resource(pod).delete();
        spawnAction.run();
      } catch (KubernetesClientException e) {
        log.warn("[OPERATOR] Recreate for {} deferred to next reconciliation: {}", entityLabel, e.getMessage());
      }
    } else if ("Unknown".equals(phase)) {
      if ("ONLINE".equals(currentStatus)) {
        log.warn("[OPERATOR] {} pod is in Unknown phase; marking OFFLINE", entityLabel);
        updater.update(entityId, "", port, "OFFLINE");
      }
    }
  }

  private void spawnWorldPod(WorldRecord world) {
    Container container = new ContainerBuilder()
      .withName("world")
      .withImage(getImage())
      .withImagePullPolicy(getImagePullPolicy())
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
      .withNewResources()
      .addToRequests("cpu", new Quantity("100m"))
      .addToRequests("memory", new Quantity("256Mi"))
      .addToLimits("cpu", new Quantity("500m"))
      .addToLimits("memory", new Quantity("512Mi"))
      .endResources()
      .addNewVolumeMount()
      .withName("jwt-keys")
      .withMountPath("/certs/jwt")
      .withReadOnly(true)
      .endVolumeMount()
      .build();

    Pod pod = new PodBuilder()
      .withNewMetadata()
      .withName("world-" + world.id())
      .withNamespace(getNamespace())
      .addToLabels("app.mmo/component", "world")
      .addToLabels("app.mmo/world-id", String.valueOf(world.id()))
      .endMetadata()
      .withNewSpec()
      .withContainers(container)
      .addNewVolume()
      .withName("jwt-keys")
      .withNewSecret()
      .withSecretName("jwt-keypair-secret")
      .addNewItem()
      .withKey("tls.crt")
      .withPath("tls.crt")
      .endItem()
      .endSecret()
      .endVolume()
      .withRestartPolicy("Always")
      .endSpec()
      .build();

    k8s.pods().inNamespace(getNamespace()).resource(pod).create();
  }

  private void spawnZonePod(ZoneInstanceRecord instance) {
    String podName = "w" + instance.worldId() + "-inst-" + instance.id() + "-z" + instance.zoneId();

    Container container = new ContainerBuilder()
      .withName("zone")
      .withImage(getImage())
      .withImagePullPolicy(getImagePullPolicy())
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
      .withNewResources()
      .addToRequests("cpu", new Quantity("100m"))
      .addToRequests("memory", new Quantity("256Mi"))
      .addToLimits("cpu", new Quantity("500m"))
      .addToLimits("memory", new Quantity("512Mi"))
      .endResources()
      .build();

    Pod pod = new PodBuilder()
      .withNewMetadata()
      .withName(podName)
      .withNamespace(getNamespace())
      .addToLabels("app.mmo/component", "zone")
      .addToLabels("app.mmo/instance-id", String.valueOf(instance.id()))
      .addToLabels("app.mmo/zone-id", String.valueOf(instance.zoneId()))
      .addToLabels("app.mmo/world-id", String.valueOf(instance.worldId()))
      .addToLabels("app.mmo/is-static", String.valueOf(instance.isStatic()))
      .endMetadata()
      .withNewSpec()
      .withContainers(container)
      .withRestartPolicy("Always")
      .endSpec()
      .build();

    k8s.pods().inNamespace(getNamespace()).resource(pod).create();
  }
}
