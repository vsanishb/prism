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
                    "..identity..", "..audit..", "..aiclient..", "..system..", "..policy..", "..claim..", "..document..");

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return "";
        }
        String rest = packageName.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
