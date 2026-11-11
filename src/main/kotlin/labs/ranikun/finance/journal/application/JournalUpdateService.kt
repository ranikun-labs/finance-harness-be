package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.journal.domain.JournalAction
import labs.ranikun.finance.journal.domain.JournalEmotion
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class InvalidJournalEditException(val field: String) : IllegalArgumentException("Invalid Journal edit")

class JournalRevisionConflictException : IllegalStateException("Journal revision conflict")

/** Explicit user correction only. Does not mutate time, owner, creation replay, context or retrospective. */
@Service
class JournalUpdateService(private val currentUserPort: CurrentUserPort, private val jdbc: JdbcTemplate) {
    @Transactional
    fun update(id: UUID, request: Map<String, Any?>): Long {
        val owner = currentUserPort.currentUserId().value
        val row = jdbc.query("SELECT type, revision FROM journals WHERE id = ? AND identity_user_id = ? AND deleted_at IS NULL FOR UPDATE",
            { rs, _ -> rs.getString("type") to rs.getLong("revision") }, id, owner).singleOrNull()
            ?: throw JournalNotFoundException()
        if (request.keys != setOf("expectedRevision", "fields")) invalid("request")
        val number = request["expectedRevision"]
        if (number !is Int && number !is Long) invalid("expectedRevision")
        val expected = (number as Number).toLong()
        if (expected < 0 || expected > MAX_REVISION) invalid("expectedRevision")
        if (expected != row.second || expected == MAX_REVISION) throw JournalRevisionConflictException()
        val fields = request["fields"] as? Map<*, *> ?: invalid("fields")
        when (row.first) {
            "investment" -> {
                if (fields.keys.any { it !in setOf("assetName", "action", "reasoning", "emotion") }) invalid("fields")
                val asset = text(fields["assetName"], "assetName", 120)
                val action = text(fields["action"], "action", 16)
                val reasoning = text(fields["reasoning"], "reasoning", 4000)
                val emotion = fields["emotion"]?.let { text(it, "emotion", 16) }
                try { JournalAction.fromWire(action); emotion?.let(JournalEmotion::fromWire) }
                catch (_: IllegalArgumentException) { invalid("action/emotion") }
                jdbc.update("UPDATE investment_journals SET asset_name=?, action=?, reasoning=?, emotion=? WHERE journal_id=?", asset, action, reasoning, emotion, id)
            }
            "study" -> {
                if (fields.keys != setOf("title", "keyContent", "openQuestions")) invalid("fields")
                val title = text(fields["title"], "title", 120)
                val content = text(fields["keyContent"], "keyContent", 6000)
                val questions = fields["openQuestions"] as? List<*> ?: invalid("openQuestions")
                if (questions.size > 10) invalid("openQuestions")
                val normalized = questions.map { text(it, "openQuestions", 500) }
                jdbc.update("UPDATE study_journals SET title=?, key_content=? WHERE journal_id=?", title, content, id)
                jdbc.update("DELETE FROM study_open_questions WHERE study_journal_id=?", id)
                normalized.forEachIndexed { index, q -> jdbc.update("INSERT INTO study_open_questions(study_journal_id,position,question) VALUES(?,?,?)", id, index, q) }
            }
            else -> error("Unsupported persisted Journal type")
        }
        jdbc.update("UPDATE journals SET revision=revision+1, updated_at=CURRENT_TIMESTAMP WHERE id=? AND identity_user_id=?", id, owner)
        return expected + 1
    }
    private fun text(value: Any?, field: String, max: Int): String {
        val raw = value as? String ?: invalid(field)
        if (raw.isBlank() || raw.length > max) invalid(field)
        return raw.trim()
    }
    private fun invalid(field: String): Nothing = throw InvalidJournalEditException(field)
    private companion object { const val MAX_REVISION = 9007199254740991L }
}
