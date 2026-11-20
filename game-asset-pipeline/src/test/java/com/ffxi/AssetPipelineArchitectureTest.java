package com.ffxi;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces module purity for {@code game-asset-pipeline} across both {@code com.example.asset}
 * and {@code com.ffxi} packages so binary DAT parsing and math remain decoupled from rendering,
 * networking, DI frameworks, and application modules.
 */
@AnalyzeClasses(packages = {"com.example.asset", "com.ffxi"}, importOptions = ImportOption.DoNotIncludeTests.class)
public class AssetPipelineArchitectureTest {

    @ArchTest
    static final ArchRule asset_pipeline_must_not_depend_on_rendering_or_ui_frameworks =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "org.lwjgl..",
                    "imgui..",
                    "java.awt..",
                    "javax.swing..",
                    "javax.imageio.."
            );

    @ArchTest
    static final ArchRule asset_pipeline_must_not_depend_on_framework_or_networking_runtimes =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "io.micronaut..",
                    "io.netty..",
                    "jakarta.inject..",
                    "io.fabric8..",
                    "org.apache.fory.."
            );

    @ArchTest
    static final ArchRule asset_pipeline_must_not_depend_on_client_server_or_tool_modules =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.common..",
                    "com.example.client..",
                    "com.example.server..",
                    "com.example.gateway..",
                    "com.example.operator..",
                    "com.example.login..",
                    "com.example.lobby..",
                    "com.example.world..",
                    "com.example.zone..",
                    "com.ffxi.viewer.."
            );
}
