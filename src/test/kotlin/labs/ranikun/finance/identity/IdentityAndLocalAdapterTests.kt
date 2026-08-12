package labs.ranikun.finance.identity

import labs.ranikun.finance.identity.adapter.LocalDevCurrentUserAdapter
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.junit.jupiter.api.Test

class IdentityAndLocalAdapterTests {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(LocalAdapterConfiguration::class.java)

    @Test
    fun identityUserIdPreservesOpaqueNonBlankValue() {
        val identityUserId = IdentityUserId("shared-user-42")

        assertThat(identityUserId.value).isEqualTo("shared-user-42")
    }

    @Test
    fun localProfileAndLocalModeExposeConfiguredCurrentUser() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                "finance.auth.mode=local",
                "finance.auth.local.identity-user-id=local-user-42",
            )
            .run { context ->
                assertThat(context).hasSingleBean(CurrentUserPort::class.java)
                assertThat(context.getBean(CurrentUserPort::class.java).currentUserId().value)
                    .isEqualTo("local-user-42")
            }
    }

    @Test
    fun localProfileWithoutLocalModeDoesNotExposeFixedUser() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                "finance.auth.local.identity-user-id=local-user-42",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(CurrentUserPort::class.java)
            }
    }

    @Test
    fun defaultProfileDoesNotExposeLocalAdapterEvenWhenModeIsLocal() {
        contextRunner
            .withPropertyValues(
                "finance.auth.mode=local",
                "finance.auth.local.identity-user-id=local-user-42",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(CurrentUserPort::class.java)
            }
    }

    @Test
    fun blankConfiguredIdentityFailsStartup() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=test",
                "finance.auth.mode=local",
                "finance.auth.local.identity-user-id= ",
            )
            .run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LocalDevCurrentUserAdapter::class)
    private class LocalAdapterConfiguration
}
