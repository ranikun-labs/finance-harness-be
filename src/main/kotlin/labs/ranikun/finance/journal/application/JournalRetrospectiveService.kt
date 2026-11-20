package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.journal.persistence.JournalRetrospectiveStore
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class RetrospectiveContent(
    val sourceRevision: Long,
    val body: String,
    val outcomeObservation: String?,
    val decisionQuality: String?,
    val nextCheck: String?,
)

data class JournalRetrospective(
    val retrospectiveId: UUID,
    val journalId: UUID,
    val createdAt: Instant,
    val content: RetrospectiveContent,
)

data class JournalRetrospectivePage(val items: List<JournalRetrospective>, val hasMore: Boolean)

@Service
class JournalRetrospectiveService(
    private val currentUser: CurrentUserPort,
    private val store: JournalRetrospectiveStore,
) {
    @Transactional
    fun append(journalId: UUID, key: String, content: RetrospectiveContent): JournalRetrospective =
        withoutSqlDetails {
            val owner = currentUser.currentUserId()
            val revision = store.lockActiveJournal(owner, journalId) ?: throw JournalNotFoundException()
            val previous = store.findReplay(journalId, key)
            if (previous != null) {
                if (previous.content != content) throw IdempotencyKeyReusedException()
                return@withoutSqlDetails previous
            }
            if (revision != content.sourceRevision) throw JournalRevisionConflictException()
            store.insert(UUID.randomUUID(), journalId, key, content)
        }

    fun list(journalId: UUID): JournalRetrospectivePage = withoutSqlDetails {
        val owner = currentUser.currentUserId()
        val items = store.list(owner, journalId, MAX_RESULTS + 1) ?: throw JournalNotFoundException()
        JournalRetrospectivePage(items.take(MAX_RESULTS), items.size > MAX_RESULTS)
    }

    fun detail(journalId: UUID, id: UUID): JournalRetrospective = withoutSqlDetails {
        store.detail(currentUser.currentUserId(), journalId, id) ?: throw JournalNotFoundException()
    }

    // The application exception handler logs causes. Keep JDBC diagnostics, which can
    // carry a row's contents, outside the logged exception chain for user-authored text.
    private inline fun <T> withoutSqlDetails(block: () -> T): T = try {
        block()
    } catch (_: DataAccessException) {
        throw IllegalStateException("Retrospective database operation failed")
    }

    private companion object {
        const val MAX_RESULTS = 50
    }
}
