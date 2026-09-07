package com.example.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example", importOptions = ImportOption.DoNotIncludeTests.class)
public class ClientDesktopIsolationArchTest {

    @ArchTest
    static final ArchRule server_must_never_depend_on_client_desktop =
            noClasses().that().resideOutsideOfPackage("com.example.client.desktop..")
                    .should().dependOnClassesThat().resideInAPackage("com.example.client.desktop..");

    @ArchTest
    static final ArchRule server_must_never_depend_on_client_sdk =
            noClasses().that().resideOutsideOfPackages("com.example.client..", "com.example.client.desktop..")
                    .should().dependOnClassesThat().resideInAPackage("com.example.client..");
}
