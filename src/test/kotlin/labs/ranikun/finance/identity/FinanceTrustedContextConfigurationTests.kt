package labs.ranikun.finance.identity

import labs.ranikun.finance.identity.adapter.TrustedContextCurrentUserAdapter
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.config.FinanceTrustedContextConfiguration
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FinanceTrustedContextConfigurationTests {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(FinanceTrustedContextConfiguration::class.java)

    @Test
    fun referenceProfileRequiresExplicitTrustedModeAndRegistersFilterAndCurrentUserPort() {
        runner.withPropertyValues(
            "spring.profiles.active=finance-trusted-context-reference",
            "finance.auth.mode=trusted-context",
            "finance.auth.trusted-context.service-secret=$PUBLIC_DUMMY_SECRET",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(CurrentUserPort::class.java)
            assertThat(context).hasSingleBean(TrustedContextCurrentUserAdapter::class.java)
            assertThat(context).hasSingleBean(FilterRegistrationBean::class.java)
            assertThat(context.getBean(FilterRegistrationBean::class.java).filter)
                .isInstanceOf(FinanceTrustedContextFilter::class.java)
        }
    }

    @Test
    fun trustedModeWithoutExplicitReferenceProfileDoesNotRegisterReceiver() {
        runner.withPropertyValues(
            "finance.auth.mode=trusted-context",
            "finance.auth.trusted-context.service-secret=$PUBLIC_DUMMY_SECRET",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CurrentUserPort::class.java)
            assertThat(context).doesNotHaveBean(FilterRegistrationBean::class.java)
        }
    }

    @Test
    fun referenceProfileFailsClosedWhenModeOrCredentialIsMissing() {
        listOf(
            arrayOf("spring.profiles.active=finance-trusted-context-reference"),
            arrayOf(
                "spring.profiles.active=finance-trusted-context-reference",
                "finance.auth.mode=local",
                "finance.auth.trusted-context.service-secret=$PUBLIC_DUMMY_SECRET",
            ),
            arrayOf(
                "spring.profiles.active=finance-trusted-context-reference",
                "finance.auth.mode=trusted-context",
            ),
        ).forEach { properties ->
            runner.withPropertyValues(*properties).run { context ->
                assertThat(context).hasFailed()
            }
        }
    }

    @Test
    fun referenceProfileRejectsProductionAndLocalProfileMixes() {
        listOf(
            "finance-trusted-context-reference,production",
            "finance-trusted-context-reference,prod",
            "finance-trusted-context-reference,local",
        ).forEach { profiles ->
            runner.withPropertyValues(
                "spring.profiles.active=$profiles",
                "finance.auth.mode=trusted-context",
                "finance.auth.trusted-context.service-secret=$PUBLIC_DUMMY_SECRET",
            ).run { context ->
                assertThat(context).hasFailed()
            }
        }
    }

    @Test
    fun referenceProfileRejectsRawAndEffectiveProductionMarkersEvenWithLocalMarkers() {
        val cases = listOf(
            listOf("spring.profiles.active=finance-trusted-context-reference", "PLATFORM_ENVIRONMENT= Production "),
            listOf("spring.profiles.active=finance-trusted-context-reference", "platform.metrics.environment=PRoD"),
            listOf(
                "spring.profiles.active=finance-trusted-context-reference,production",
                "PLATFORM_ENVIRONMENT=local",
                "platform.metrics.environment=local",
            ),
        )
        cases.forEach { extraProperties ->
            runner.withPropertyValues(
                "finance.auth.mode=trusted-context",
                "finance.auth.trusted-context.service-secret=$PUBLIC_DUMMY_SECRET",
                *extraProperties.toTypedArray(),
            ).run { context ->
                assertThat(context).hasFailed()
            }
        }
    }

    companion object {
        private const val PUBLIC_DUMMY_SECRET =
            "Finance-D1-Reference-Public-Dummy-Secret-Do-Not-Use-In-Production-2026"
    }
}
