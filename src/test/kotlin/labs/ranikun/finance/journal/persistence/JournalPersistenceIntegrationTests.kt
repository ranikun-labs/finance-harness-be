package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalCreateCommand
import labs.ranikun.finance.journal.application.ResolvedJournalTime
import labs.ranikun.finance.journal.domain.JournalAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalPersistenceIntegrationTests {

    @Autowired
    lateinit var journalCreateApplicationService: JournalCreateApplicationService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun investmentCreatePersistsOwnerTimeTripleAndDetail() {
        val occurredAt = Instant.parse("2026-08-12T05:30:15.123Z")
        val occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30, 15, 123_000_000)

        val journalId = journalCreateApplicationService.create(
            JournalCreateCommand.Investment(
                assetName = "ETF",
                action = JournalAction.BUY,
                reasoning = "thesis",
                emotion = null,
                occurredAt = ResolvedJournalTime(occurredAt, occurredLocalAt, "Asia/Seoul"),
            ),
        )

        val base = jdbcTemplate.queryForMap(
            """
            SELECT id, identity_user_id, type, occurred_at AT TIME ZONE 'UTC' AS occurred_at_utc,
                   occurred_local_at::text AS occurred_local_at_text, occurred_time_zone
            FROM journals WHERE id = ?
            """.trimIndent(),
            journalId,
        )
        val detail = jdbcTemplate.queryForMap(
            "SELECT journal_id, journal_type, asset_name, action, reasoning, emotion FROM investment_journals WHERE journal_id = ?",
            journalId,
        )

        assertThat(base["id"]).isEqualTo(journalId)
        assertThat(base["identity_user_id"]).isEqualTo("test-user-rpl-50")
        assertThat(base["type"]).isEqualTo("investment")
        assertThat(base["occurred_at_utc"].toString()).isEqualTo("2026-08-12 05:30:15.123")
        assertThat(base["occurred_local_at_text"]).isEqualTo("2026-08-12 14:30:15.123")
        assertThat(base["occurred_time_zone"]).isEqualTo("Asia/Seoul")
        assertThat(detail["journal_id"]).isEqualTo(journalId)
        assertThat(detail["journal_type"]).isEqualTo("investment")
        assertThat(detail["asset_name"]).isEqualTo("ETF")
        assertThat(detail["action"]).isEqualTo("buy")
        assertThat(detail["reasoning"]).isEqualTo("thesis")
        assertThat(detail["emotion"]).isNull()
    }

    @Test
    fun studyCreatePersistsOrderedDuplicateQuestions() {
        val questions = listOf("first", "same", "same", "last")
        val journalId = journalCreateApplicationService.create(
            JournalCreateCommand.Study(
                title = "Study",
                keyContent = "content",
                openQuestions = questions,
                occurredAt = ResolvedJournalTime(
                    occurredAt = Instant.parse("2026-08-12T05:30:00Z"),
                    occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30),
                    occurredTimeZone = "Asia/Seoul",
                ),
            ),
        )

        val detail = jdbcTemplate.queryForMap(
            "SELECT journal_id, journal_type, title, key_content FROM study_journals WHERE journal_id = ?",
            journalId,
        )
        val persistedQuestions = jdbcTemplate.query(
            "SELECT position, question FROM study_open_questions WHERE study_journal_id = ? ORDER BY position",
            { resultSet, _ -> resultSet.getInt("position") to resultSet.getString("question") },
            journalId,
        )

        assertThat(detail["journal_id"]).isEqualTo(journalId)
        assertThat(detail["journal_type"]).isEqualTo("study")
        assertThat(detail["title"]).isEqualTo("Study")
        assertThat(detail["key_content"]).isEqualTo("content")
        assertThat(persistedQuestions).containsExactly(
            0 to "first",
            1 to "same",
            2 to "same",
            3 to "last",
        )
    }
}
