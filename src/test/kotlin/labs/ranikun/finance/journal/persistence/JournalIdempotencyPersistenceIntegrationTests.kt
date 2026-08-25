package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalIdempotencyPersistenceIntegrationTests {

    @Autowired
    lateinit var flyway: org.flywaydb.core.Flyway

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var postgresContainer: PostgreSQLContainer

    @BeforeEach
    fun clearRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun appliesV1ToV2AgainstPostgreSQL18Point4WithTheStableConstraintNames() {
        assertThat(postgresContainer.databaseName).isEqualTo("finance_db")
        assertThat(jdbcTemplate.queryForObject("SELECT current_setting('server_version')", String::class.java))
            .startsWith("18.4")
        assertThat(flyway.info().applied().mapNotNull { it.version?.version })
            .containsExactly("1", "2")

        assertThat(jdbcTemplate.queryForList(
            """
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'journal_idempotency_records'::regclass
            ORDER BY conname
            """.trimIndent(),
            String::class.java,
        )).contains(
            "pk_journal_idempotency_records",
            "uq_journal_idempotency_records_namespace",
            "uq_journal_idempotency_records_journal_id",
            "fk_journal_idempotency_records_journal",
            "ck_journal_idempotency_records_identity_user_id_nonblank",
            "ck_journal_idempotency_records_operation",
            "ck_journal_idempotency_records_idempotency_key",
            "ck_journal_idempotency_records_request_fingerprint",
        )
    }

    @Test
    fun namespaceAndJournalUniqueConstraintsAreDatabaseEnforced() {
        val journalId = java.util.UUID.fromString("0190e8f0-8a00-7000-8000-000000000001")
        val secondJournalId = java.util.UUID.fromString("0190e8f0-8a00-7000-8000-000000000002")
        insertJournal(journalId, "test-user-rpl-96")
        insertJournal(secondJournalId, "other-user-rpl-96")
        val fingerprint = "v1:sha256:${"a".repeat(64)}"
        insertRecord(journalId, "same-key", fingerprint)
        insertRecord(secondJournalId, "same-key", fingerprint, owner = "other-user-rpl-96")

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_idempotency_records",
            Int::class.java,
        )).isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject(
            "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_journal_idempotency_records_journal'",
            String::class.java,
        )).isEqualTo("r")

        assertConstraintViolation("uq_journal_idempotency_records_namespace") {
            insertRecord(journalId, "same-key", fingerprint)
        }
        assertConstraintViolation("uq_journal_idempotency_records_journal_id") {
            insertRecord(journalId, "different-key", fingerprint)
        }
    }

    private fun insertJournal(journalId: java.util.UUID, owner: String) {
        jdbcTemplate.update(
            """
            INSERT INTO journals (
                id, identity_user_id, type, occurred_at, occurred_local_at,
                occurred_time_zone, created_at, updated_at
            ) VALUES (?, ?, 'investment', TIMESTAMPTZ '2026-08-12 05:30:00+00',
                      TIMESTAMP '2026-08-12 14:30:00', 'Asia/Seoul',
                      TIMESTAMPTZ '2026-08-12 05:30:00+00', TIMESTAMPTZ '2026-08-12 05:30:00+00')
            """.trimIndent(),
            journalId,
            owner,
        )
    }

    private fun insertRecord(
        journalId: java.util.UUID,
        key: String,
        fingerprint: String,
        owner: String = "test-user-rpl-96",
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO journal_idempotency_records (
                identity_user_id, operation, idempotency_key, request_fingerprint, journal_id, created_at
            ) VALUES (?, 'journal.create.v1', ?, ?, ?, TIMESTAMPTZ '2026-08-12 05:30:00+00')
            """.trimIndent(),
            owner,
            key,
            fingerprint,
            journalId,
        )
    }

    private fun assertConstraintViolation(expectedConstraint: String, action: () -> Unit) {
        val thrown = catchThrowable(action)
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException::class.java)
        val messages = generateSequence(thrown) { it.cause }
            .mapNotNull { it.message }
            .toList()
        assertThat(messages.joinToString("\n")).contains(expectedConstraint)
    }
}
