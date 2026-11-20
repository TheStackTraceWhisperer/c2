package com.example.client;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces layer boundaries for {@code client-sdk}, keeping transport/state handling independent
 * of desktop rendering/UI stacks and server runtime internals.
 */
@AnalyzeClasses(packages = "com.example.client", importOptions = ImportOption.DoNotIncludeTests.class)
public class ClientSdkArchitectureTest {

    @ArchTest
    static final ArchRule client_sdk_must_not_depend_on_desktop_or_rendering_stack =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.client.desktop..",
                    "org.lwjgl..",
                    "imgui..",
                    "com.ffxi..",
                    "com.example.asset.."
            );

    @ArchTest
    static final ArchRule client_sdk_must_not_depend_on_server_runtime =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.server..",
                    "com.example.gateway..",
                    "com.example.operator..",
                    "com.example.login.handler..",
                    "com.example.login.auth..",
                    "com.example.lobby.handler..",
                    "com.example.lobby.registry..",
                    "com.example.world.handler..",
                    "com.example.world.registry..",
                    "com.example.zone.handler..",
                    "com.example.zone.engine..",
                    "com.example.zone.collision..",
                    "com.example.zone.patrol..",
                    "com.example.zone.spatial..",
                    "com.example.zone.state.."
            );
}
