package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.journal.application.JournalIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedJournalIdTestConfiguration::class)
@ActiveProfiles("test")
class JournalIdempotencyConstraintSafetyIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
        jdbcTemplate.update(
            """
            INSERT INTO journals (
                id, identity_user_id, type, occurred_at, occurred_local_at,
                occurred_time_zone, created_at, updated_at
            ) VALUES (?, 'test-user-rpl-50', 'investment', ?, ?, 'Asia/Seoul', ?, ?)
            """.trimIndent(),
            FIXED_JOURNAL_ID,
            java.sql.Timestamp.from(Instant.parse("2026-08-12T05:30:00Z")),
            java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 8, 12, 14, 30)),
            java.sql.Timestamp.from(Instant.parse("2026-08-12T05:30:00Z")),
            java.sql.Timestamp.from(Instant.parse("2026-08-12T05:30:00Z")),
        )
        jdbcTemplate.update(
            """
            INSERT INTO journal_idempotency_records (
                identity_user_id, operation, idempotency_key, request_fingerprint, journal_id, created_at
            ) VALUES ('another-owner', 'journal.create.v1', 'existing-journal', ?, ?, ?)
            """.trimIndent(),
            "v1:sha256:${"a".repeat(64)}",
            FIXED_JOURNAL_ID,
            java.sql.Timestamp.from(Instant.parse("2026-08-12T05:30:00Z")),
        )
    }

    @Test
    fun unrelatedJournalPrimaryKeyViolationFailsClosedWithoutRecoveryOrIdempotencyRecord() {
        mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "unrelated-unique")
                .content(
                    """
                    {
                      "type": "investment",
                      "assetName": "ETF",
                      "occurredAt": "2026-08-12T14:30",
                      "timeZone": "Asia/Seoul",
                      "action": "buy",
                      "reasoning": "collision"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("internal_error"))
            .andExpect(jsonPath("$.journalId").doesNotExist())

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journals", Int::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_idempotency_records", Int::class.java))
            .isEqualTo(1)
    }

    companion object {
        val FIXED_JOURNAL_ID: UUID = UUID.fromString("0190e8f0-8a00-7000-8000-000000000010")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FixedJournalIdTestConfiguration {

    @Bean
    @Primary
    fun fixedJournalIdGenerator(): JournalIdGenerator = object : JournalIdGenerator {
        override fun generate(): UUID = JournalIdempotencyConstraintSafetyIntegrationTests.FIXED_JOURNAL_ID
    }
}
