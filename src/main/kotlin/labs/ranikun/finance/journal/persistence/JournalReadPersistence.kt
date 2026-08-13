package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalCursor
import labs.ranikun.finance.journal.application.JournalDetailRecord
import labs.ranikun.finance.journal.application.JournalListRecord
import labs.ranikun.finance.journal.application.JournalReadStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcJournalReadStore(
    private val jdbcTemplate: JdbcTemplate,
) : JournalReadStore {

    override fun findDetail(owner: IdentityUserId, journalId: UUID): JournalDetailRecord? =
        jdbcTemplate.query(
            DETAIL_SQL,
            DETAIL_ROW_MAPPER,
            owner.value,
            journalId,
        ).singleOrNull()

    override fun findStudyOpenQuestions(owner: IdentityUserId, journalId: UUID): List<String> =
        jdbcTemplate.query(
            STUDY_OPEN_QUESTIONS_SQL,
            { resultSet, _ -> resultSet.getString("question") },
            owner.value,
            journalId,
        )

    override fun findList(owner: IdentityUserId, cursor: JournalCursor?, limitPlusOne: Int): List<JournalListRecord> {
        val sql = if (cursor == null) LIST_SQL else LIST_SQL_WITH_CURSOR
        val arguments = if (cursor == null) {
            arrayOf<Any>(owner.value, limitPlusOne)
        } else {
            arrayOf<Any>(
                owner.value,
                Timestamp.from(cursor.occurredAtUtc),
                Timestamp.from(cursor.occurredAtUtc),
                cursor.journalId,
                limitPlusOne,
            )
        }
        return jdbcTemplate.query(sql, LIST_ROW_MAPPER, *arguments)
    }

    private companion object {
        const val DETAIL_SQL = """
            SELECT j.id AS journal_id,
                   j.type,
                   j.occurred_local_at,
                   j.occurred_time_zone,
                   j.created_at,
                   j.updated_at,
                   i.asset_name,
                   i.action,
                   i.reasoning,
                   i.emotion,
                   s.title,
                   s.key_content
            FROM journals j
            LEFT JOIN investment_journals i ON i.journal_id = j.id
            LEFT JOIN study_journals s ON s.journal_id = j.id
            WHERE j.identity_user_id = ?
              AND j.id = ?
            """

        const val STUDY_OPEN_QUESTIONS_SQL = """
            SELECT q.question
            FROM study_open_questions q
            JOIN journals j ON j.id = q.study_journal_id
            WHERE j.identity_user_id = ?
              AND q.study_journal_id = ?
            ORDER BY q.position ASC
            """

        const val LIST_SQL = """
            SELECT j.id AS journal_id,
                   j.type,
                   j.occurred_at,
                   j.occurred_local_at,
                   j.occurred_time_zone,
                   i.asset_name,
                   i.action,
                   s.title
            FROM journals j
            LEFT JOIN investment_journals i ON i.journal_id = j.id
            LEFT JOIN study_journals s ON s.journal_id = j.id
            WHERE j.identity_user_id = ?
            ORDER BY j.occurred_at DESC, j.id DESC
            LIMIT ?
            """

        const val LIST_SQL_WITH_CURSOR = """
            SELECT j.id AS journal_id,
                   j.type,
                   j.occurred_at,
                   j.occurred_local_at,
                   j.occurred_time_zone,
                   i.asset_name,
                   i.action,
                   s.title
            FROM journals j
            LEFT JOIN investment_journals i ON i.journal_id = j.id
            LEFT JOIN study_journals s ON s.journal_id = j.id
            WHERE j.identity_user_id = ?
              AND (
                  j.occurred_at < ?
                  OR (
                      j.occurred_at = ?
                      AND j.id < ?
                  )
              )
            ORDER BY j.occurred_at DESC, j.id DESC
            LIMIT ?
            """

        val DETAIL_ROW_MAPPER = RowMapper { resultSet: ResultSet, _: Int ->
            JournalDetailRecord(
                journalId = resultSet.getObject("journal_id", UUID::class.java),
                type = resultSet.getString("type"),
                occurredLocalAt = resultSet.getObject("occurred_local_at", LocalDateTime::class.java),
                timeZone = resultSet.getString("occurred_time_zone"),
                createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                updatedAt = resultSet.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                assetName = resultSet.getString("asset_name"),
                action = resultSet.getString("action"),
                reasoning = resultSet.getString("reasoning"),
                emotion = resultSet.getString("emotion"),
                title = resultSet.getString("title"),
                keyContent = resultSet.getString("key_content"),
            )
        }

        val LIST_ROW_MAPPER = RowMapper { resultSet: ResultSet, _: Int ->
            JournalListRecord(
                journalId = resultSet.getObject("journal_id", UUID::class.java),
                type = resultSet.getString("type"),
                occurredAtUtc = resultSet.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                occurredLocalAt = resultSet.getObject("occurred_local_at", LocalDateTime::class.java),
                timeZone = resultSet.getString("occurred_time_zone"),
                assetName = resultSet.getString("asset_name"),
                action = resultSet.getString("action"),
                title = resultSet.getString("title"),
            )
        }
    }
}
