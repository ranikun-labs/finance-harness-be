package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

interface JournalReadStore {

    fun findDetail(owner: IdentityUserId, journalId: UUID): JournalDetailRecord?

    fun findStudyOpenQuestions(owner: IdentityUserId, journalId: UUID): List<String>
}

data class JournalDetailRecord(
    val journalId: UUID,
    val type: String,
    val occurredLocalAt: LocalDateTime,
    val timeZone: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val assetName: String?,
    val action: String?,
    val reasoning: String?,
    val emotion: String?,
    val title: String?,
    val keyContent: String?,
)

sealed interface JournalDetail {

    val journalId: UUID
    val occurredLocalAt: LocalDateTime
    val timeZone: String
    val createdAt: Instant
    val updatedAt: Instant

    data class Investment(
        override val journalId: UUID,
        override val occurredLocalAt: LocalDateTime,
        override val timeZone: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        val assetName: String,
        val action: String,
        val reasoning: String,
        val emotion: String?,
    ) : JournalDetail

    data class Study(
        override val journalId: UUID,
        override val occurredLocalAt: LocalDateTime,
        override val timeZone: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        val title: String,
        val keyContent: String,
        val openQuestions: List<String>,
    ) : JournalDetail
}

class JournalNotFoundException : NoSuchElementException("Journal not found.")

@Service
class JournalReadApplicationService(
    private val currentUserPort: CurrentUserPort,
    private val journalReadStore: JournalReadStore,
) {

    fun findDetail(journalId: UUID): JournalDetail {
        val owner = currentUserPort.currentUserId()
        val record = journalReadStore.findDetail(owner, journalId) ?: throw JournalNotFoundException()

        return when (record.type) {
            "investment" -> JournalDetail.Investment(
                journalId = record.journalId,
                occurredLocalAt = record.occurredLocalAt,
                timeZone = record.timeZone,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                assetName = requireNotNull(record.assetName),
                action = requireNotNull(record.action),
                reasoning = requireNotNull(record.reasoning),
                emotion = record.emotion,
            )

            "study" -> JournalDetail.Study(
                journalId = record.journalId,
                occurredLocalAt = record.occurredLocalAt,
                timeZone = record.timeZone,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                title = requireNotNull(record.title),
                keyContent = requireNotNull(record.keyContent),
                openQuestions = journalReadStore.findStudyOpenQuestions(owner, journalId),
            )

            else -> error("Unknown persisted Journal type: ${record.type}")
        }
    }
}
