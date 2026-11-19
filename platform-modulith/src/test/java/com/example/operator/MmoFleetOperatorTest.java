package com.example.operator;

import com.example.server.entity.WorldRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.repository.WorldJdbcRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MmoFleetOperatorTest {

  private FakeK8sState k8sState;
  private KubernetesClient k8sClient;
  private Map<Integer, WorldRecord> worlds;
  private Map<Integer, ZoneInstanceRecord> zones;
  private List<EndpointUpdate> worldUpdates;
  private List<EndpointUpdate> zoneUpdates;
  private MmoFleetOperator operator;

  record EndpointUpdate(int id, String host, int port, String status) {}

  static class FakeK8sState {
    final Map<String, Pod> pods = new LinkedHashMap<>();
    final List<String> deletedPods = new ArrayList<>();
    final List<String> createdPods = new ArrayList<>();
    final AtomicInteger listCalls = new AtomicInteger();
  }

  @BeforeEach
  void setUp() {
    k8sState = new FakeK8sState();
    k8sClient = createFakeKubernetesClient(k8sState);
    worlds = new LinkedHashMap<>();
    zones = new LinkedHashMap<>();
    worldUpdates = new ArrayList<>();
    zoneUpdates = new ArrayList<>();

    WorldJdbcRepository worldRepo = createWorldRepoProxy();
    ZoneInstanceJdbcRepository zoneRepo = createZoneRepoProxy();
    operator = new MmoFleetOperator(k8sClient, worldRepo, zoneRepo);
  }

  @Test
  @DisplayName("init() logs configuration without running synchronous reconcile() in @PostConstruct")
  void testInitDoesNotReconcileSynchronously() {
    worlds.put(1, new WorldRecord(1, "Asura", "", 9996, "PENDING", 4000, Instant.now()));

    operator.init();

    assertThat(k8sState.listCalls.get()).isZero();
    assertThat(k8sState.pods).isEmpty();
  }

  @Test
  @DisplayName("KubernetesClientFactory declares preDestroy = close on singleton bean")
  void testKubernetesClientFactoryPreDestroyClose() throws NoSuchMethodException {
    Method factoryMethod = KubernetesClientFactory.class.getMethod("kubernetesClient");
    Bean beanAnnotation = factoryMethod.getAnnotation(Bean.class);

    assertThat(beanAnnotation).isNotNull();
    assertThat(beanAnnotation.preDestroy()).isEqualTo("close");
  }

  @Test
  @DisplayName("MmoFleetOperator imagePullPolicy defaults to Never and supports @Value configuration")
  void testImagePullPolicyDefaultAndValueAnnotation() throws NoSuchFieldException {
    Value valueAnnotation = MmoFleetOperator.class.getDeclaredField("imagePullPolicy").getAnnotation(Value.class);
    assertThat(valueAnnotation).isNotNull();
    assertThat(valueAnnotation.value()).isEqualTo("${app.operator.image-pull-policy:Never}");

    worlds.put(1, new WorldRecord(1, "Asura", "", 9996, "PENDING", 4000, Instant.now()));
    operator.overrideConfigForTest("mmo-ns", "registry.internal/mmo:v2", "IfNotPresent");
    operator.reconcile();

    Pod spawned = k8sState.pods.get("world-1");
    assertThat(spawned).isNotNull();
    assertThat(spawned.getMetadata().getNamespace()).isEqualTo("mmo-ns");
    Container container = spawned.getSpec().getContainers().getFirst();
    assertThat(container.getImage()).isEqualTo("registry.internal/mmo:v2");
    assertThat(container.getImagePullPolicy()).isEqualTo("IfNotPresent");
  }

  @Test
  @DisplayName("Provisions missing World and Zone pods with resources, public-only JWT cert, and labels")
  void testProvisionMissingWorldAndZonePods() {
    worlds.put(1, new WorldRecord(1, "Asura", "", 9996, "PENDING", 4000, Instant.now()));
    zones.put(10, new ZoneInstanceRecord(10, 1, 230, "DEFAULT", "", 9997, "PENDING", true, Instant.now()));

    operator.reconcile();

    assertThat(k8sState.createdPods).containsExactlyInAnyOrder("world-1", "w1-inst-10-z230");

    Pod worldPod = k8sState.pods.get("world-1");
    assertThat(worldPod.getMetadata().getLabels())
      .containsEntry("app.mmo/component", "world")
      .containsEntry("app.mmo/world-id", "1");

    Container worldContainer = worldPod.getSpec().getContainers().getFirst();
    assertThat(worldContainer.getImage()).isEqualTo("platform-modulith:latest");
    assertThat(worldContainer.getImagePullPolicy()).isEqualTo("Never");
    assertThat(worldContainer.getResources()).isNotNull();
    assertThat(worldContainer.getResources().getRequests())
      .containsEntry("cpu", new Quantity("100m"))
      .containsEntry("memory", new Quantity("256Mi"));
    assertThat(worldContainer.getResources().getLimits())
      .containsEntry("cpu", new Quantity("500m"))
      .containsEntry("memory", new Quantity("512Mi"));

    assertThat(worldContainer.getVolumeMounts())
      .extracting(VolumeMount::getName, VolumeMount::getMountPath, VolumeMount::getReadOnly)
      .containsExactly(org.assertj.core.groups.Tuple.tuple("jwt-keys", "/certs/jwt", true));

    Volume jwtVolume = worldPod.getSpec().getVolumes().stream()
      .filter(v -> "jwt-keys".equals(v.getName()))
      .findFirst()
      .orElseThrow();
    SecretVolumeSource secretSource = jwtVolume.getSecret();
    assertThat(secretSource.getSecretName()).isEqualTo("jwt-keypair-secret");
    assertThat(secretSource.getItems()).hasSize(1);
    assertThat(secretSource.getItems().getFirst().getKey()).isEqualTo("tls.crt");
    assertThat(secretSource.getItems().getFirst().getPath()).isEqualTo("tls.crt");

    Pod zonePod = k8sState.pods.get("w1-inst-10-z230");
    assertThat(zonePod.getMetadata().getLabels())
      .containsEntry("app.mmo/component", "zone")
      .containsEntry("app.mmo/instance-id", "10")
      .containsEntry("app.mmo/zone-id", "230")
      .containsEntry("app.mmo/world-id", "1")
      .containsEntry("app.mmo/is-static", "true");
    Container zoneContainer = zonePod.getSpec().getContainers().getFirst();
    assertThat(zoneContainer.getResources()).isNotNull();
    assertThat(zoneContainer.getResources().getRequests()).containsKey("cpu").containsKey("memory");
    assertThat(zoneContainer.getResources().getLimits()).containsKey("cpu").containsKey("memory");
  }

  @Test
  @DisplayName("Syncs Running pod IPs to DB as ONLINE and avoids redundant updates when unchanged")
  void testRunningPodIpSync() {
    worlds.put(1, new WorldRecord(1, "Asura", "", 9996, "PENDING", 4000, Instant.now()));
    zones.put(10, new ZoneInstanceRecord(10, 1, 230, "DEFAULT", "", 9997, "PENDING", true, Instant.now()));

    k8sState.pods.put("world-1", buildPod("world-1", Map.of("app.mmo/component", "world", "app.mmo/world-id", "1"), "Running", "10.42.0.10"));
    k8sState.pods.put("w1-inst-10-z230", buildPod("w1-inst-10-z230", Map.of("app.mmo/component", "zone", "app.mmo/instance-id", "10"), "Running", "10.42.0.11"));

    operator.reconcile();

    assertThat(worldUpdates).containsExactly(new EndpointUpdate(1, "10.42.0.10", 9996, "ONLINE"));
    assertThat(zoneUpdates).containsExactly(new EndpointUpdate(10, "10.42.0.11", 9997, "ONLINE"));

    // Second reconciliation with matching host + ONLINE should be a no-op
    operator.reconcile();
    assertThat(worldUpdates).hasSize(1);
    assertThat(zoneUpdates).hasSize(1);
  }

  @Test
  @DisplayName("Marks previously ONLINE World and Zone DB records OFFLINE when pods disappear and recreates them")
  void testMissingOnlinePodMarksOfflineAndRecreates() {
    worlds.put(1, new WorldRecord(1, "Asura", "10.42.0.10", 9996, "ONLINE", 4000, Instant.now()));
    zones.put(10, new ZoneInstanceRecord(10, 1, 230, "DEFAULT", "10.42.0.11", 9997, "ONLINE", true, Instant.now()));

    operator.reconcile();

    assertThat(worldUpdates).containsExactly(new EndpointUpdate(1, "", 9996, "OFFLINE"));
    assertThat(zoneUpdates).containsExactly(new EndpointUpdate(10, "", 9997, "OFFLINE"));
    assertThat(worlds.get(1).status()).isEqualTo("OFFLINE");
    assertThat(zones.get(10).status()).isEqualTo("OFFLINE");
    assertThat(k8sState.pods).containsKeys("world-1", "w1-inst-10-z230");
  }

  @Test
  @DisplayName("Marks Failed pods OFFLINE, deletes them, and recreates replacement pods")
  void testFailedPodMarksOfflineDeletesAndRecreates() {
    worlds.put(1, new WorldRecord(1, "Asura", "10.42.0.10", 9996, "ONLINE", 4000, Instant.now()));
    zones.put(10, new ZoneInstanceRecord(10, 1, 230, "DEFAULT", "10.42.0.11", 9997, "ONLINE", true, Instant.now()));

    k8sState.pods.put("world-1", buildPod("world-1", Map.of("app.mmo/component", "world", "app.mmo/world-id", "1"), "Failed", "10.42.0.10"));
    k8sState.pods.put("w1-inst-10-z230", buildPod("w1-inst-10-z230", Map.of("app.mmo/component", "zone", "app.mmo/instance-id", "10"), "Failed", "10.42.0.11"));

    operator.reconcile();

    assertThat(worldUpdates).containsExactly(new EndpointUpdate(1, "", 9996, "OFFLINE"));
    assertThat(zoneUpdates).containsExactly(new EndpointUpdate(10, "", 9997, "OFFLINE"));
    assertThat(k8sState.deletedPods).containsExactlyInAnyOrder("world-1", "w1-inst-10-z230");
    assertThat(k8sState.createdPods).containsExactlyInAnyOrder("world-1", "w1-inst-10-z230");
    assertThat(k8sState.pods).containsKeys("world-1", "w1-inst-10-z230");
    assertThat(k8sState.pods.get("world-1").getStatus()).isNull();
  }

  @Test
  @DisplayName("Marks Unknown phase pods OFFLINE if previously ONLINE without deleting them")
  void testUnknownPodPhaseMarksOffline() {
    worlds.put(1, new WorldRecord(1, "Asura", "10.42.0.10", 9996, "ONLINE", 4000, Instant.now()));
    k8sState.pods.put("world-1", buildPod("world-1", Map.of("app.mmo/component", "world", "app.mmo/world-id", "1"), "Unknown", null));

    operator.reconcile();

    assertThat(worldUpdates).containsExactly(new EndpointUpdate(1, "", 9996, "OFFLINE"));
    assertThat(k8sState.deletedPods).isEmpty();
    assertThat(k8sState.createdPods).isEmpty();
  }

  @Test
  @DisplayName("Malformed numeric pod labels are skipped without aborting valid World and Zone reconciliation")
  void testMalformedPodLabelResilience() {
    worlds.put(1, new WorldRecord(1, "Asura", "", 9996, "PENDING", 4000, Instant.now()));
    zones.put(10, new ZoneInstanceRecord(10, 1, 230, "DEFAULT", "", 9997, "PENDING", true, Instant.now()));

    k8sState.pods.put("world-bad", buildPod("world-bad", Map.of("app.mmo/component", "world", "app.mmo/world-id", "not-a-number"), "Running", "10.42.0.99"));
    k8sState.pods.put("world-1", buildPod("world-1", Map.of("app.mmo/component", "world", "app.mmo/world-id", "1"), "Running", "10.42.0.10"));
    k8sState.pods.put("zone-bad", buildPod("zone-bad", Map.of("app.mmo/component", "zone", "app.mmo/instance-id", "NaN"), "Running", "10.42.0.98"));
    k8sState.pods.put("w1-inst-10-z230", buildPod("w1-inst-10-z230", Map.of("app.mmo/component", "zone", "app.mmo/instance-id", "10"), "Running", "10.42.0.11"));

    operator.reconcile();

    assertThat(worldUpdates).containsExactly(new EndpointUpdate(1, "10.42.0.10", 9996, "ONLINE"));
    assertThat(zoneUpdates).containsExactly(new EndpointUpdate(10, "10.42.0.11", 9997, "ONLINE"));
  }

  private static Pod buildPod(String name, Map<String, String> labels, String phase, String podIp) {
    PodBuilder builder = new PodBuilder()
      .withNewMetadata()
      .withName(name)
      .withNamespace("default")
      .withLabels(labels)
      .endMetadata();
    if (phase != null || podIp != null) {
      builder.withNewStatus()
        .withPhase(phase)
        .withPodIP(podIp)
        .endStatus();
    }
    return builder.build();
  }

  private WorldJdbcRepository createWorldRepoProxy() {
    return (WorldJdbcRepository) Proxy.newProxyInstance(
      WorldJdbcRepository.class.getClassLoader(),
      new Class<?>[]{WorldJdbcRepository.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "findAll" -> new ArrayList<>(worlds.values());
        case "findById" -> Optional.ofNullable(worlds.get((Integer) args[0]));
        case "updateEndpoint" -> {
          int id = (Integer) args[0];
          String host = (String) args[1];
          int port = (Integer) args[2];
          String status = (String) args[3];
          worldUpdates.add(new EndpointUpdate(id, host, port, status));
          WorldRecord existing = worlds.get(id);
          if (existing != null) {
            worlds.put(id, new WorldRecord(existing.id(), existing.name(), host, port, status, existing.maxCapacity(), Instant.now()));
          }
          yield null;
        }
        default -> handleObjectMethod(proxy, method, args);
      }
    );
  }

  private ZoneInstanceJdbcRepository createZoneRepoProxy() {
    return (ZoneInstanceJdbcRepository) Proxy.newProxyInstance(
      ZoneInstanceJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ZoneInstanceJdbcRepository.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "findAll" -> new ArrayList<>(zones.values());
        case "findById" -> Optional.ofNullable(zones.get((Integer) args[0]));
        case "updateEndpoint" -> {
          int id = (Integer) args[0];
          String host = (String) args[1];
          int port = (Integer) args[2];
          String status = (String) args[3];
          zoneUpdates.add(new EndpointUpdate(id, host, port, status));
          ZoneInstanceRecord existing = zones.get(id);
          if (existing != null) {
            zones.put(id, new ZoneInstanceRecord(existing.id(), existing.worldId(), existing.zoneId(),
              existing.instanceTag(), host, port, status, existing.isStatic(), Instant.now()));
          }
          yield null;
        }
        default -> handleObjectMethod(proxy, method, args);
      }
    );
  }

  @SuppressWarnings("unchecked")
  private static KubernetesClient createFakeKubernetesClient(FakeK8sState state) {
    ClassLoader cl = KubernetesClient.class.getClassLoader();
    InvocationHandler podOpHandler = createPodOpHandler(state, null, null);
    Object mixedOp = Proxy.newProxyInstance(
      cl,
      new Class<?>[]{MixedOperation.class, NonNamespaceOperation.class, FilterWatchListDeletable.class},
      podOpHandler
    );
    return (KubernetesClient) Proxy.newProxyInstance(
      cl,
      new Class<?>[]{KubernetesClient.class},
      (proxy, method, args) -> {
        if ("pods".equals(method.getName())) {
          return mixedOp;
        }
        return handleObjectMethod(proxy, method, args);
      }
    );
  }

  private static InvocationHandler createPodOpHandler(FakeK8sState state, String labelKey, String labelVal) {
    ClassLoader cl = KubernetesClient.class.getClassLoader();
    return (proxy, method, args) -> switch (method.getName()) {
      case "inNamespace" -> Proxy.newProxyInstance(
        cl,
        new Class<?>[]{NonNamespaceOperation.class, FilterWatchListDeletable.class},
        createPodOpHandler(state, labelKey, labelVal)
      );
      case "withLabel" -> Proxy.newProxyInstance(
        cl,
        new Class<?>[]{FilterWatchListDeletable.class},
        createPodOpHandler(state, (String) args[0], (String) args[1])
      );
      case "list" -> {
        state.listCalls.incrementAndGet();
        List<Pod> matching = new ArrayList<>();
        for (Pod p : state.pods.values()) {
          if (labelKey == null || (p.getMetadata() != null && p.getMetadata().getLabels() != null
            && labelVal.equals(p.getMetadata().getLabels().get(labelKey)))) {
            matching.add(p);
          }
        }
        yield new PodListBuilder().withItems(matching).build();
      }
      case "resource" -> {
        Pod targetPod = (Pod) args[0];
        yield Proxy.newProxyInstance(
          cl,
          new Class<?>[]{PodResource.class},
          (resProxy, resMethod, resArgs) -> switch (resMethod.getName()) {
            case "create" -> {
              String name = targetPod.getMetadata().getName();
              state.pods.put(name, targetPod);
              state.createdPods.add(name);
              yield targetPod;
            }
            case "delete" -> {
              String name = targetPod.getMetadata().getName();
              state.pods.remove(name);
              state.deletedPods.add(name);
              yield List.of(new StatusDetails());
            }
            default -> handleObjectMethod(resProxy, resMethod, resArgs);
          }
        );
      }
      default -> handleObjectMethod(proxy, method, args);
    };
  }

  private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "toString" -> proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> args != null && args.length == 1 && proxy == args[0];
      default -> throw new UnsupportedOperationException("Unexpected proxy method call: " + method.getName());
    };
  }
}
