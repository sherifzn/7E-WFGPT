package com.sevenewf.workflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

final class ModuleDependencyTest {
  @Test
  void contractsDoNotDependOnBackend() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.sevenewf.workflow.contracts");

    noClasses().should().dependOnClassesThat().resideInAnyPackage("..backend..").check(classes);
  }

  @Test
  void backendDoesNotDependOnArchitectureTests() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.sevenewf.workflow.backend");

    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..architecture..")
        .check(classes);
  }
}
