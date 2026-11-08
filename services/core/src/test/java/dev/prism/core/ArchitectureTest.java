package dev.prism.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(packages = "dev.prism.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "dev.prism.core.";

    /** A module's internal package belongs to that module alone. */
    @ArchTest
    static final ArchRule modulesUseOnlyPublicApis = noClasses().should(new ArchCondition<JavaClass>("reach into another module's internal package") {
        @Override
        public void check(JavaClass source, ConditionEvents events) {
            String sourceModule = moduleOf(source.getPackageName());
            source.getDirectDependenciesFromSelf().forEach(dependency -> {
                String target = dependency.getTargetClass().getPackageName();
                if (target.startsWith(ROOT) && target.contains(".internal") && !moduleOf(target).equals(sourceModule)) {
                    events.add(SimpleConditionEvent.satisfied(dependency, dependency.getDescription()));
                }
            });
        }
    });

    @ArchTest
    static final ArchRule platformDependsOnNoFeature = noClasses()
            .that().resideInAPackage("..platform..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..identity..", "..audit..", "..aiclient..", "..system..", "..policy..", "..claim..", "..document..",
                    "..intelligence..", "..workflow..", "..policyknowledge..", "..retrieval..", "..risk..", "..review..");

    /** Status flows one way, so that claims know nothing of how they are processed. */
    @ArchTest
    static final ArchRule claimsDoNotKnowAboutProcessing = noClasses()
            .that().resideInAPackage("..claim..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..intelligence..", "..workflow..", "..aiclient..");

    @ArchTest
    static final ArchRule policiesDoNotKnowAboutTheirKnowledge = noClasses()
            .that().resideInAPackage("..policy..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..policyknowledge..", "..retrieval..", "..workflow..", "..aiclient..");

    @ArchTest
    static final ArchRule intelligenceDoesNotKnowAboutWorkflows = noClasses()
            .that().resideInAnyPackage("..intelligence..", "..policyknowledge..", "..retrieval..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..workflow..", "io.temporal..");

    /** Temporal stays inside one module, so that it can never leak into business logic. */
    @ArchTest
    static final ArchRule onlyTheWorkflowModuleUsesTemporal = noClasses()
            .that().resideOutsideOfPackage("..workflow..")
            .should().dependOnClassesThat()
            .resideInAPackage("io.temporal..");

    /**
     * Rules and scoring are pure functions of their input (D-082): no database, no other claims'
     * modules, no AI service, and no workflow. Core loads what they read.
     */
    @ArchTest
    static final ArchRule riskRulesArePure = noClasses()
            .that().resideInAPackage("..risk..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..intelligence..", "..workflow..", "..aiclient..", "..claim..", "..policy..", "..document..",
                    "org.springframework.jdbc..", "io.temporal..");

    /** Review decides; it never runs processing itself. It asks the workflow through the outbox. */
    @ArchTest
    static final ArchRule reviewDoesNotKnowAboutWorkflows = noClasses()
            .that().resideInAPackage("..review..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..workflow..", "..aiclient..", "io.temporal..");

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return "";
        }
        String rest = packageName.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
