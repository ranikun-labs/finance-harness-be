package labs.ranikun.finance.identity

import labs.ranikun.finance.identity.adapter.LocalDevCurrentUserAdapter
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalCursor
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalDetailRecord
import labs.ranikun.finance.journal.application.JournalListRecord
import labs.ranikun.finance.journal.application.JournalReadApplicationService
import labs.ranikun.finance.journal.application.JournalReadStore
import org.assertj.core.api.Assertions.assertThat
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.junit.jupiter.api.Test
import java.util.UUID

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
    fun testProfileAndLocalModeExposeConfiguredCurrentUser() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=test",
                "finance.auth.mode=local",
                "finance.auth.local.identity-user-id=test-user-42",
            )
            .run { context ->
                assertThat(context).hasSingleBean(CurrentUserPort::class.java)
                assertThat(context.getBean(CurrentUserPort::class.java).currentUserId().value)
                    .isEqualTo("test-user-42")
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
    fun productionProfileCombinationsNeverExposeLocalAdapter() {
        val productionCombinations = listOf(
            "production",
            "production,local",
            "local,production",
            "production,test",
            "test,production",
        )

        productionCombinations.forEach { profiles ->
            contextRunner
                .withPropertyValues("spring.profiles.active=$profiles")
                .run { context ->
                    assertThat(context).doesNotHaveBean(CurrentUserPort::class.java)
                }

            contextRunner
                .withPropertyValues(
                    "spring.profiles.active=$profiles",
                    "finance.auth.mode=local",
                    "finance.auth.local.identity-user-id=local-user-42",
                )
                .run { context ->
                    assertThat(context).doesNotHaveBean(CurrentUserPort::class.java)
                }
        }
    }

    @Test
    fun productionJournalWiringFailsWithoutSharedCurrentUserPort() {
        ApplicationContextRunner()
            .withUserConfiguration(ProductionJournalConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=production,local",
                "finance.auth.mode=local",
                "finance.auth.local.identity-user-id=local-user-42",
            )
            .run { context ->
                assertThat(context).hasFailed()
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

    @Configuration(proxyBeanMethods = false)
    @Import(
        LocalDevCurrentUserAdapter::class,
        JournalCreateApplicationService::class,
        JournalReadApplicationService::class,
    )
    private class ProductionJournalConfiguration {

        @Bean
        fun journalReadStore(): JournalReadStore = object : JournalReadStore {
            override fun findDetail(owner: IdentityUserId, journalId: UUID): JournalDetailRecord? = null

            override fun findStudyOpenQuestions(owner: IdentityUserId, journalId: UUID): List<String> = emptyList()

            override fun findList(
                owner: IdentityUserId,
                cursor: JournalCursor?,
                limitPlusOne: Int,
            ): List<JournalListRecord> = emptyList()
        }
    }
}
