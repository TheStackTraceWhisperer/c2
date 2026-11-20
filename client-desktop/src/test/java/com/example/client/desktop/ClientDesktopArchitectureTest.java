package com.example.client.desktop;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces module and layer isolation for {@code client-desktop}:
 * prevents coupling to server internals, prevents re-introducing standalone {@code ffxi-tools}
 * entrypoints, and keeps {@code com.ffxi.viewer} rendering classes independent of Micronaut/Netty/ImGui.
 */
@AnalyzeClasses(
        packages = {"com.example.client.desktop", "com.ffxi.viewer"},
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ClientDesktopArchitectureTest {

    @ArchTest
    static final ArchRule desktop_client_must_not_depend_on_server_runtime =
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

    @ArchTest
    static final ArchRule desktop_client_must_not_depend_on_ffxi_tools =
            noClasses().should().dependOnClassesThat().resideInAPackage("com.ffxi.viewer.debug..");

    @ArchTest
    static final ArchRule desktop_client_must_not_host_standalone_tool_entrypoints =
            noClasses().that().resideInAPackage("com.ffxi.viewer..")
                    .should().haveSimpleNameEndingWith("Viewer")
                    .orShould().haveSimpleName("MapShaderSandbox")
                    .orShould().haveSimpleName("GlbRenderer")
                    .orShould().haveSimpleName("App")
                    .orShould().resideInAPackage("com.ffxi.viewer.debug..");

    @ArchTest
    static final ArchRule ffxi_viewer_runtime_must_stay_decoupled_from_app_shell_di_and_networking =
            noClasses().that().resideInAPackage("com.ffxi.viewer..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.example.client..",
                            "io.micronaut..",
                            "io.netty..",
                            "imgui.."
                    );
}
