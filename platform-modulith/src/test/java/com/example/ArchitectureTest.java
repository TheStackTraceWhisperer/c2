package com.example;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

  // --- 1. Service-to-Service Isolation Rules (Handler & Registry Level) ---

  @ArchTest
  static final ArchRule world_should_not_depend_on_other_services =
    noClasses().that().resideInAnyPackage("com.example.world.handler..", "com.example.world.registry..", "com.example.world")
      .should().dependOnClassesThat().resideInAnyPackage(
        "com.example.login.handler..", "com.example.login.auth..", "com.example.login",
        "com.example.lobby.handler..", "com.example.lobby.registry..", "com.example.lobby",
        "com.example.zone.handler..", "com.example.zone.engine..", "com.example.zone.spatial..", "com.example.zone.collision..", "com.example.zone",
        "com.example.gateway.."
      );

  @ArchTest
  static final ArchRule zone_should_not_depend_on_other_services =
    noClasses().that().resideInAnyPackage("com.example.zone.handler..", "com.example.zone.engine..", "com.example.zone.spatial..", "com.example.zone.collision..", "com.example.zone")
      .should().dependOnClassesThat().resideInAnyPackage(
        "com.example.login.handler..", "com.example.login.auth..", "com.example.login",
        "com.example.lobby.handler..", "com.example.lobby.registry..", "com.example.lobby",
        "com.example.world.handler..", "com.example.world.registry..", "com.example.world",
        "com.example.gateway.."
      );

  @ArchTest
  static final ArchRule lobby_should_not_depend_on_other_services =
    noClasses().that().resideInAnyPackage("com.example.lobby.handler..", "com.example.lobby.registry..", "com.example.lobby")
      .should().dependOnClassesThat().resideInAnyPackage(
        "com.example.login.handler..", "com.example.login.auth..", "com.example.login",
        "com.example.world.handler..", "com.example.world.registry..", "com.example.world",
        "com.example.zone.handler..", "com.example.zone.engine..", "com.example.zone.spatial..", "com.example.zone.collision..", "com.example.zone",
        "com.example.gateway.."
      );

  @ArchTest
  static final ArchRule login_should_not_depend_on_other_services =
    noClasses().that().resideInAnyPackage("com.example.login.handler..", "com.example.login.auth..", "com.example.login")
      .should().dependOnClassesThat().resideInAnyPackage(
        "com.example.lobby.handler..", "com.example.lobby.registry..", "com.example.lobby",
        "com.example.world.handler..", "com.example.world.registry..", "com.example.world",
        "com.example.zone.handler..", "com.example.zone.engine..", "com.example.zone.spatial..", "com.example.zone.collision..", "com.example.zone",
        "com.example.gateway.."
      );

  // --- 2. Edge Gateway Isolation Rules ---

  @ArchTest
  static final ArchRule gateway_should_only_relay_and_not_touch_domain_handlers =
    noClasses().that().resideInAPackage("..gateway..")
      .should().dependOnClassesThat().resideInAnyPackage(
        "..login.handler..",
        "..lobby.handler..",
        "..world.handler..",
        "..zone.handler.."
      );

  // --- 3. Client vs Server Layer Boundaries ---

  @ArchTest
  static final ArchRule server_must_never_depend_on_client =
    noClasses().that().resideOutsideOfPackage("com.example.client..")
      .should().dependOnClassesThat().resideInAPackage("com.example.client..");

  // --- 4. Common Protocol Purity (Cannot depend on server runtime internals) ---

  @ArchTest
  static final ArchRule common_protocol_must_not_depend_on_server_infrastructure =
    noClasses().that().resideInAPackage("com.example.common..")
      .should().dependOnClassesThat().resideInAnyPackage(
        "com.example.server..",
        "com.example.gateway..",
        "com.example.login.handler..",
        "com.example.login.auth..",
        "com.example.lobby.handler..",
        "com.example.lobby.registry..",
        "com.example.world.handler..",
        "com.example.world.registry..",
        "com.example.zone.handler..",
        "com.example.zone.engine..",
        "com.example.zone.spatial..",
        "com.example.zone.collision.."
      );
}