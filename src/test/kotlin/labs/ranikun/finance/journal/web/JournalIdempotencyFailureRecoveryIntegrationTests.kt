package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalIdempotencyRecord
import labs.ranikun.finance.journal.application.JournalIdempotencyStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class, FailOnceIdempotencyStoreTestConfiguration::class)
@ActiveProfiles("test")
class JournalIdempotencyFailureRecoveryIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var idempotencyStore: FailOnceIdempotencyStore

    @BeforeEach
    fun clearRowsAndArmFailure() {
        idempotencyStore.failNextSave = true
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun failedIdempotencyPersistenceRollsBackEverythingAndRetryCanCreate() {
        val key = "failure-then-retry"
        val request = post("/finance/journals")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key)
            .content(investmentJson())

        mockMvc.perform(request)
            .andExpect(status().isInternalServerError)

        assertThat(count("journals")).isZero
        assertThat(count("investment_journals")).isZero
        assertThat(count("journal_idempotency_records")).isZero

        mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(investmentJson()),
        )
            .andExpect(status().isCreated)

        assertThat(count("journals")).isEqualTo(1)
        assertThat(count("investment_journals")).isEqualTo(1)
        assertThat(count("journal_idempotency_records")).isEqualTo(1)
    }

    private fun count(table: String): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table",
        Int::class.java,
    ) ?: error("Missing count for $table")

    private fun investmentJson(): String = """
        {
          "type": "investment",
          "assetName": "ETF",
          "occurredAt": "2026-08-12T14:30:15.123",
          "timeZone": "Asia/Seoul",
          "action": "buy",
          "reasoning": "failure recovery"
        }
    """.trimIndent()
}

@TestConfiguration(proxyBeanMethods = false)
class FailOnceIdempotencyStoreTestConfiguration {

    @Bean
    @Primary
    fun failOnceIdempotencyStore(
        @Qualifier("jpaJournalIdempotencyStore") delegate: JournalIdempotencyStore,
    ): FailOnceIdempotencyStore = FailOnceIdempotencyStore(delegate)
}

class FailOnceIdempotencyStore(
    private val delegate: JournalIdempotencyStore,
) : JournalIdempotencyStore {

    @Volatile
    var failNextSave: Boolean = true

    override fun find(
        owner: IdentityUserId,
        operation: String,
        idempotencyKey: String,
    ): JournalIdempotencyRecord? = delegate.find(owner, operation, idempotencyKey)

    override fun save(
        owner: IdentityUserId,
        operation: String,
        idempotencyKey: String,
        requestFingerprint: String,
        journalId: UUID,
        createdAt: Instant,
    ) {
        delegate.save(owner, operation, idempotencyKey, requestFingerprint, journalId, createdAt)
        if (failNextSave) {
            failNextSave = false
            throw ForcedIdempotencyPersistenceFailure()
        }
    }
}

class ForcedIdempotencyPersistenceFailure : RuntimeException("forced idempotency persistence failure")
