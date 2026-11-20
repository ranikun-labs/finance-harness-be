package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalRetrospective
import labs.ranikun.finance.journal.application.RetrospectiveContent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JournalRetrospectiveStore(private val jdbc: JdbcTemplate) {
    fun lockActiveJournal(owner: IdentityUserId, journalId: UUID): Long? =
        jdbc.query(
            """
            SELECT revision FROM journals WHERE id = ? AND identity_user_id = ?
            AND deleted_at IS NULL FOR UPDATE
            """.trimIndent(),
            { row, _ -> row.getLong("revision") },
            journalId, owner.value,
        ).singleOrNull()

    fun findReplay(journalId: UUID, key: String): JournalRetrospective? =
        jdbc.query(
            "SELECT * FROM journal_retrospectives WHERE journal_id = ? AND idempotency_key = ?",
            MAP_ROW, journalId, key,
        ).singleOrNull()

    fun insert(id: UUID, journalId: UUID, key: String, content: RetrospectiveContent): JournalRetrospective =
        jdbc.query(
            """
            INSERT INTO journal_retrospectives
                (id, journal_id, idempotency_key, source_revision, body, outcome_observation, decision_quality, next_check)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *
            """.trimIndent(),
            MAP_ROW,
            id, journalId, key, content.sourceRevision, content.body,
            content.outcomeObservation, content.decisionQuality, content.nextCheck,
        ).single()

    fun list(owner: IdentityUserId, journalId: UUID, limit: Int): List<JournalRetrospective>? {
        val rows = jdbc.query(
            """
            SELECT r.* FROM journals j
            LEFT JOIN LATERAL (
                SELECT * FROM journal_retrospectives WHERE journal_id = j.id
                ORDER BY created_at DESC, id DESC LIMIT ?
            ) r ON true
            WHERE j.id = ? AND j.identity_user_id = ? AND j.deleted_at IS NULL
            ORDER BY r.created_at DESC, r.id DESC
            """.trimIndent(),
            { row, index -> if (row.getObject("id") == null) null else MAP_ROW.mapRow(row, index) },
            limit, journalId, owner.value,
        )
        return if (rows.isEmpty()) null else rows.filterNotNull()
    }

    fun detail(owner: IdentityUserId, journalId: UUID, id: UUID): JournalRetrospective? =
        jdbc.query(
            """
            SELECT r.* FROM journal_retrospectives r JOIN journals j ON j.id = r.journal_id
            WHERE j.id = ? AND j.identity_user_id = ? AND j.deleted_at IS NULL AND r.id = ?
            """.trimIndent(),
            MAP_ROW, journalId, owner.value, id,
        ).singleOrNull()

    private companion object {
        val MAP_ROW = RowMapper<JournalRetrospective> { row, _ ->
            JournalRetrospective(
                retrospectiveId = row.getObject("id", UUID::class.java),
                journalId = row.getObject("journal_id", UUID::class.java),
                createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                content = RetrospectiveContent(
                    row.getLong("source_revision"),
                    row.getString("body"),
                    row.getString("outcome_observation"),
                    row.getString("decision_quality"),
                    row.getString("next_check"),
                ),
            )
        }
    }
}
