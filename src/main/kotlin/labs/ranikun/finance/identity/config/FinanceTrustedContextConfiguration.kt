package labs.ranikun.finance.identity.config

import jakarta.servlet.DispatcherType
import labs.ranikun.finance.identity.adapter.TrustedContextCurrentUserAdapter
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.util.EnumSet

/**
 * Explicit non-production registration. The candidate's production TLS, credential
 * lifecycle, and risk gates remain outside this fixture profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile("finance-trusted-context-reference")
class FinanceTrustedContextConfiguration : EnvironmentAware {

    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    @Bean
    fun financeTrustedContextReferenceSettings(
        @Value("\${finance.auth.mode:}") mode: String,
        @Value("\${finance.auth.trusted-context.service-secret:}") serviceSecret: String,
    ): FinanceTrustedContextReferenceSettings {
        val profiles = (environment.activeProfiles + environment.defaultProfiles)
            .map(String::lowercase)
            .toSet()
        check(profiles.none { it.contains("prod") }) {
            "The trusted Finance context reference profile cannot be combined with a production profile"
        }
        check(
            !isProductionMarker(environment.getProperty("PLATFORM_ENVIRONMENT")) &&
                !isProductionMarker(environment.getProperty("platform.metrics.environment")) &&
                !isProductionMarker(environment.getProperty("FINANCE_METRICS_ENVIRONMENT")) &&
                !isProductionMarker(environment.getProperty("management.metrics.tags.environment")),
        ) {
            "The trusted Finance context reference profile cannot be combined with a production environment marker"
        }
        check("local" !in profiles) {
            "The trusted Finance context reference profile cannot be combined with the local profile"
        }
        check(mode == TRUSTED_CONTEXT_MODE) {
            "The trusted Finance context reference profile requires finance.auth.mode=trusted-context"
        }
        check(serviceSecret.length >= MIN_SECRET_LENGTH && serviceSecret.all { it.code in PRINTABLE_ASCII }) {
            "The trusted Finance context reference profile requires an explicit printable fixture caller value"
        }
        check(',' !in serviceSecret) {
            "The trusted Finance context caller value cannot contain a comma"
        }
        return FinanceTrustedContextReferenceSettings(serviceSecret)
    }

    @Bean
    fun trustedContextCurrentUserAdapter(
        @Suppress("UNUSED_PARAMETER") settings: FinanceTrustedContextReferenceSettings,
    ): TrustedContextCurrentUserAdapter = TrustedContextCurrentUserAdapter()

    @Bean
    fun financeTrustedContextFilterRegistration(
        settings: FinanceTrustedContextReferenceSettings,
    ): FilterRegistrationBean<FinanceTrustedContextFilter> = FilterRegistrationBean(
        FinanceTrustedContextFilter(settings.serviceSecret),
    ).apply {
        addUrlPatterns("/*")
        setDispatcherTypes(EnumSet.allOf(DispatcherType::class.java))
        order = Ordered.HIGHEST_PRECEDENCE + 20
        setName("financeTrustedContextReferenceFilter")
    }

    data class FinanceTrustedContextReferenceSettings(val serviceSecret: String)

    private fun isProductionMarker(value: String?): Boolean =
        value?.trim()?.lowercase() in setOf("prod", "production")

    companion object {
        const val FINANCE_TRUSTED_CONTEXT_REFERENCE_PROFILE = "finance-trusted-context-reference"
        const val TRUSTED_CONTEXT_MODE = "trusted-context"
        private const val MIN_SECRET_LENGTH = 32
        private val PRINTABLE_ASCII = 0x21..0x7e
    }
}
