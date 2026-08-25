package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalCreateCommand
import labs.ranikun.finance.journal.application.JournalStore
import labs.ranikun.finance.journal.application.ResolvedJournalTime
import labs.ranikun.finance.journal.domain.JournalAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class, RollbackTestConfiguration::class)
@ActiveProfiles("test")
class JournalTransactionRollbackIntegrationTests {

    @Autowired
    lateinit var journalCreateApplicationService: JournalCreateApplicationService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun checkedPersistenceFailureRollsBackBaseDetailAndQuestionsTogether() {
        assertThatThrownBy {
            journalCreateApplicationService.create(
                JournalCreateCommand.Study(
                    title = "Study",
                    keyContent = "content",
                    openQuestions = listOf("question"),
                    occurredAt = ResolvedJournalTime(
                        occurredAt = Instant.parse("2026-08-12T05:30:00Z"),
                        occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30),
                        occurredTimeZone = "Asia/Seoul",
                    ),
                ),
                idempotencyKey = "rollback-study",
            )
        }.isInstanceOf(ForcedRollbackException::class.java)

        assertThat(count("journals")).isZero()
        assertThat(count("study_journals")).isZero()
        assertThat(count("study_open_questions")).isZero()
        assertThat(count("investment_journals")).isZero()
        assertThat(count("journal_idempotency_records")).isZero()
    }

    private fun count(table: String): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table",
        Int::class.java,
    ) ?: error("Missing count")
}

@TestConfiguration(proxyBeanMethods = false)
class RollbackTestConfiguration {

    @Bean
    @Primary
    fun failingJournalStore(
        @Qualifier("jpaJournalStore") delegate: JournalStore,
    ): JournalStore = object : JournalStore {
        override fun save(
            journalId: UUID,
            owner: IdentityUserId,
            command: JournalCreateCommand,
        ) {
            delegate.save(journalId, owner, command)
            throw ForcedRollbackException()
        }
    }
}

class ForcedRollbackException : Exception("forced rollback")
