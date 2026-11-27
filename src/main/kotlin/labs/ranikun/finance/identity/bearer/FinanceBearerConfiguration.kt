package labs.ranikun.finance.identity.bearer

import jakarta.servlet.DispatcherType
import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.EnumSet

/** Disabled by default. No revocation implementation or production key is supplied here. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "finance.auth.bearer", name = ["enabled"], havingValue = "true")
class FinanceBearerConfiguration {
    @Bean
    fun financeBearerVerifier(
        environment: Environment,
        revocation: FinanceRevocationPort,
        @Value("\${finance.auth.bearer.issuer}") issuer: String,
        @Value("\${finance.auth.bearer.audience}") audience: String,
        @Value("\${finance.auth.bearer.access-type-claim}") accessClaim: String,
        @Value("\${finance.auth.bearer.access-type-value}") accessValue: String,
        @Value("\${finance.auth.bearer.rsa-public-key-der-base64}") encodedKey: String,
    ): FinanceBearerVerifier {
        check((environment.activeProfiles + environment.defaultProfiles).map(String::lowercase).none {
            it == "local" || it == "finance-trusted-context-reference" || it.contains("prod")
        }) { "Bearer candidate cannot be combined with local, reference, or production profiles" }
        check(listOf("PLATFORM_ENVIRONMENT", "platform.metrics.environment", "FINANCE_METRICS_ENVIRONMENT", "management.metrics.tags.environment")
            .mapNotNull(environment::getProperty)
            .none { it.trim().lowercase().contains("prod") }) {
            "Bearer candidate cannot be used in a production environment"
        }
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey))) as RSAPublicKey
        return FinanceBearerVerifier(FinanceBearerPolicy(issuer, audience, accessClaim, accessValue, key), revocation)
    }

    @Bean
    fun financeBearerFilterRegistration(verifier: FinanceBearerVerifier): FilterRegistrationBean<FinanceBearerFilter> =
        FilterRegistrationBean(FinanceBearerFilter(verifier)).apply {
            setDispatcherTypes(EnumSet.allOf(DispatcherType::class.java))
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }

    @Bean
    fun financeBearerCurrentUser(): CurrentUserPort = FinanceBearerCurrentUserAdapter()
}
