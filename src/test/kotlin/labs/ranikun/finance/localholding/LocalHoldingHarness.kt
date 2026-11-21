package labs.ranikun.finance.localholding

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** Disposable test-only HTTP harness. Identity is fixed at process startup, never from a request. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class, FlywayAutoConfiguration::class])
@Import(LocalHoldingController::class)
class LocalHoldingHarness {
    @Bean fun currentUser(): CurrentUserPort = object : CurrentUserPort {
        override fun currentUserId() = IdentityUserId(System.getenv("HOLDING_TEST_OWNER") ?: "test-owner")
    }
}

fun main(args: Array<String>) {
    SpringApplication(LocalHoldingHarness::class.java).apply {
        setAdditionalProfiles("test")
        setDefaultProperties(mapOf("finance.local-holding.enabled" to "true", "server.address" to "127.0.0.1"))
    }.run(*args)
}
