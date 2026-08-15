package com.example;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

  @ArchTest
  static final ArchRule world_should_not_depend_on_other_services =
    noClasses().that().resideInAPackage("..world..")
      .should().dependOnClassesThat().resideInAnyPackage("..login..", "..lobby..", "..gateway..");

  @ArchTest
  static final ArchRule lobby_should_not_depend_on_other_services =
    noClasses().that().resideInAPackage("..lobby..")
      .should().dependOnClassesThat().resideInAnyPackage("..login..", "..world..", "..gateway..");

  @ArchTest
  static final ArchRule login_should_not_depend_on_other_services =
    noClasses().that().resideInAPackage("..login..")
      .should().dependOnClassesThat().resideInAnyPackage("..lobby..", "..world..", "..gateway..");

  @ArchTest
  static final ArchRule gateway_should_only_relay_and_not_touch_domain_handlers =
    noClasses().that().resideInAPackage("..gateway..")
      .should().dependOnClassesThat().resideInAnyPackage("..login.handler..", "..lobby.handler..", "..world.handler..");

  @ArchTest
  static final ArchRule server_must_never_depend_on_client =
    noClasses().that().resideOutsideOfPackage("com.example.client..")
      .should().dependOnClassesThat().resideInAPackage("com.example.client..");
}