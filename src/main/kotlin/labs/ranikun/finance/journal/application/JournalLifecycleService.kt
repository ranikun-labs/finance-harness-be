package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class JournalLifecycleState(val journalId: String, val revision: Long, val state: String)
class JournalDeletedException(val journalId: UUID, val revision: Long) : IllegalStateException("Journal deleted")

@Service
class JournalLifecycleService(private val currentUserPort: CurrentUserPort, private val jdbc: JdbcTemplate) {
    @Transactional
    fun transition(id: UUID, request: Map<String, Any?>, restore: Boolean): JournalLifecycleState {
        if (request.keys != setOf("expectedRevision")) throw InvalidJournalEditException("request")
        val raw = request["expectedRevision"]
        if (raw !is Int && raw !is Long) throw InvalidJournalEditException("expectedRevision")
        val expected = (raw as Number).toLong()
        if (expected < 0 || expected > MAX_REVISION) throw InvalidJournalEditException("expectedRevision")
        val owner = currentUserPort.currentUserId()
        val row = lock(owner, id)
        val current = (row["revision"] as Number).toLong()
        val deleted = row["deleted_at"] != null
        val desiredDeleted = !restore
        // Confirm only the observed current state on a lost-response retry; never reapply it.
        if (deleted == desiredDeleted && expected < MAX_REVISION && current == expected + 1) {
            return JournalLifecycleState(id.toString(), current, if (deleted) "deleted" else "active")
        }
        if (deleted == desiredDeleted || current != expected || current == MAX_REVISION) {
            throw JournalRevisionConflictException()
        }
        jdbc.update("UPDATE journals SET deleted_at=" + (if (restore) "NULL" else "CURRENT_TIMESTAMP") +
            ", revision=revision+1, updated_at=CURRENT_TIMESTAMP WHERE id=? AND identity_user_id=?", id, owner.value)
        return JournalLifecycleState(id.toString(), current + 1, if (restore) "active" else "deleted")
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireActiveForReplay(owner: IdentityUserId, id: UUID) {
        val row = lock(owner, id)
        if (row["deleted_at"] != null) throw JournalDeletedException(id, (row["revision"] as Number).toLong())
    }

    fun deletedList(): Map<String, Any> {
        val rows = jdbc.query("SELECT id,type,revision,deleted_at FROM journals WHERE identity_user_id=? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC,id DESC LIMIT 101",
            { rs, _ -> mapOf<String, Any>("journalId" to rs.getObject("id", UUID::class.java).toString(),
                "type" to rs.getString("type"), "revision" to rs.getLong("revision"),
                "deletedAt" to rs.getTimestamp("deleted_at").toInstant().toString()) }, currentUserPort.currentUserId().value)
        return mapOf("items" to rows.take(100), "hasMore" to (rows.size > 100))
    }

    private fun lock(owner: IdentityUserId, id: UUID) = jdbc.queryForList(
        "SELECT revision,deleted_at FROM journals WHERE id=? AND identity_user_id=? FOR UPDATE", id, owner.value)
        .singleOrNull() ?: throw JournalNotFoundException()

    private companion object { const val MAX_REVISION = 9007199254740991L }
}
