package financeholding

import labs.ranikun.finance.holding.HoldingController

import labs.ranikun.finance.identity.config.FinanceTrustedContextConfiguration
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Import

/** Disposable test HTTP server using the existing verified-context filter and adapter. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = [
    DataSourceAutoConfiguration::class,
    HibernateJpaAutoConfiguration::class,
    FlywayAutoConfiguration::class,
])
@Import(FinanceTrustedContextConfiguration::class, HoldingController::class)
class HoldingReferenceHarness

fun main(args: Array<String>) {
    SpringApplication(HoldingReferenceHarness::class.java).apply {
        setAdditionalProfiles(FinanceTrustedContextConfiguration.FINANCE_TRUSTED_CONTEXT_REFERENCE_PROFILE)
        setDefaultProperties(mapOf(
            "finance.auth.mode" to "trusted-context",
            "finance.holding.reference.enabled" to "true",
            "server.address" to "127.0.0.1",
            "management.endpoint.health.validate-group-membership" to "false",
        ))
    }.run(*args)
}
