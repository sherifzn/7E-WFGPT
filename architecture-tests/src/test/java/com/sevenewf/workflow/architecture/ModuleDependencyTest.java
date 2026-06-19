package com.sevenewf.workflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ModuleDependencyTest {
  private static final Set<String> DOMAIN_ALLOWED_PACKAGES =
      Set.of("java", "com.sevenewf.workflow.domain");
  private static final DescribedPredicate<JavaClass> DOMAIN_OR_JDK_CLASS =
      new DescribedPredicate<>("domain or JDK class") {
        @Override
        public boolean test(JavaClass javaClass) {
          return DOMAIN_ALLOWED_PACKAGES.stream().anyMatch(javaClass.getPackageName()::startsWith);
        }
      };

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

  @Test
  void domainDoesNotDependOnAdaptersBackendContractsOrFrameworks() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.sevenewf.workflow.domain");

    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..adapters..",
            "..backend..",
            "..contracts..",
            "org..",
            "jakarta..",
            "javax..",
            "com.fasterxml..")
        .check(classes);

    ArchRuleDefinition.classes()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .onlyDependOnClassesThat(DOMAIN_OR_JDK_CLASS)
        .check(classes);
  }

  @Test
  void adaptersDoNotDependOnBackend() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.sevenewf.workflow.adapters");

    noClasses().should().dependOnClassesThat().resideInAnyPackage("..backend..").check(classes);
  }
}
