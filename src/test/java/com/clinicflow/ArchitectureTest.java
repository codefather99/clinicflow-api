package com.clinicflow;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The seam. If this fails, someone crossed the line between Henry's side and Deji's side. */
@AnalyzeClasses(packages = "com.clinicflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule web_only_sees_ports =
            noClasses().that().resideInAPackage("com.clinicflow.web..")
                    .should().dependOnClassesThat(
                            resideInAPackage("com.clinicflow.domain..")
                                    .and(not(resideInAPackage("com.clinicflow.domain.port.."))));

    @ArchTest
    static final ArchRule domain_never_sees_web =
            noClasses().that().resideInAPackage("com.clinicflow.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.clinicflow.web..");

    @ArchTest
    static final ArchRule ports_depend_on_nothing_else =
            noClasses().that().resideInAPackage("com.clinicflow.domain.port..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.clinicflow.web..", "com.clinicflow.fake..");
}
