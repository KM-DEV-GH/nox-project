package com.nox.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.nox.NoxApplication;
import com.nox.adapter.out.identityprovider.SigningKeySource;
import com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessageTransport;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/** Enforces the Clean Architecture layer rules of constitution Principle X (research R17). */
@AnalyzeClasses(
        packages = "com.nox",
        importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.ExcludePackageInfo.class})
class ArchitectureTest {

    /** package-info carries only Spring Modulith {@code @NamedInterface} metadata, not layer code. */
    static final class ExcludePackageInfo implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("package-info");
        }
    }

    @ArchTest
    static final ArchRule domainDependsOnlyOnJavaAndItself = classes()
            .that()
            .resideInAPackage("com.nox.domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.nox.domain..")
            .allowEmptyShould(true);

    /** The framework exceptions are {@code @Transactional} and stereotypes (constitution Principle X). */
    @ArchTest
    static final ArchRule applicationDependsOnlyOnJavaDomainItselfAndSpringAnnotations = classes()
            .that()
            .resideInAPackage("com.nox.application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "com.nox.domain..",
                    "com.nox.application..",
                    "org.springframework.transaction.annotation..",
                    "org.springframework.stereotype..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainAndApplicationEventsAreSpringFree = noClasses()
            .that()
            .resideInAnyPackage("com.nox.domain..", "com.nox.application.event..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersDoNotDependOnOutboundAdapters = noClasses()
            .that()
            .resideInAPackage("com.nox.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.nox.adapter.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnInboundAdapters = noClasses()
            .that()
            .resideInAPackage("com.nox.adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.nox.adapter.in..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyTheCompositionRootDependsOnInfrastructure = noClasses()
            .that()
            .resideOutsideOfPackage("com.nox.infrastructure..")
            .and()
            .doNotBelongToAnyOf(NoxApplication.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.nox.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotDependOnUseCaseImplementations = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.nox.application.service..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersCallUseCasesThroughInboundPorts = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.nox.application.port.in..")
            .allowEmptyShould(true);

    /** The payment domain is self-contained: links, partners and callers reach it only through the application. */
    @ArchTest
    static final ArchRule paymentDomainDependsOnlyOnJavaAndItself = classes()
            .that()
            .resideInAPackage("com.nox.domain.payment..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.nox.domain.payment..")
            .allowEmptyShould(true);

    /** Payment-core is reached only through {@code PaymentProcessorPort}; only the composition root builds the adapter. */
    @ArchTest
    static final ArchRule onlyTheCompositionRootSeesThePaymentCoreAdapter = noClasses()
            .that()
            .resideOutsideOfPackages("com.nox.adapter.out.paymentcore..", "com.nox.infrastructure..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.nox.adapter.out.paymentcore..")
            .allowEmptyShould(true);

    /** Resilience for payment-core stays inside its adapter (constitution Principle XIII). */
    @ArchTest
    static final ArchRule resilience4jStaysInThePaymentCoreAdapter = noClasses()
            .that()
            .resideOutsideOfPackage("com.nox.adapter.out.paymentcore..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.resilience4j..")
            .allowEmptyShould(true);

    /** Payment controllers call use cases only; they never touch persistence or payment-core ports. */
    @ArchTest
    static final ArchRule paymentWebAdaptersUseOnlyInboundPorts = noClasses()
            .that()
            .resideInAPackage("com.nox.adapter.in.web.payment..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.nox.application.port.out..", "com.nox.application.service..")
            .allowEmptyShould(true);

    /**
     * Outbound adapters that the composition root may build itself, each for a reason constitution
     * Principle X allows. Every other outbound adapter binds its own settings record and is
     * component-scanned.
     */
    private static final Set<Class<?>> CONSTRUCTED_BY_INFRASTRUCTURE = Set.of(
            // Lifecycle hooks: started and closed by its @Bean definition.
            SigningKeySource.class,
            // Not a bean: handed to Spring Modulith's event externalizer listener.
            PartnerLinkMessageTransport.class);

    /** Constitution Principle X: adapters own their settings and are component-scanned, never built by config. */
    @ArchTest
    static final ArchRule infrastructureDoesNotConstructOutboundAdapters = noClasses()
            .that()
            .resideInAPackage("com.nox.infrastructure..")
            .should()
            .callConstructorWhere(new DescribedPredicate<JavaConstructorCall>(
                    "the target is an outbound adapter type not allowed to be built by the composition root") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    JavaClass target = call.getTargetOwner();
                    return target.getPackageName().startsWith("com.nox.adapter.out")
                            && CONSTRUCTED_BY_INFRASTRUCTURE.stream().noneMatch(target::isEquivalentTo);
                }
            })
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields().should().beAnnotatedWith(Autowired.class).allowEmptyShould(true);
}
