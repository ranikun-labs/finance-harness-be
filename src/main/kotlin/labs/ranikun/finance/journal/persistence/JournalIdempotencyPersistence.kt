package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalIdempotencyRecord
import labs.ranikun.finance.journal.application.JournalIdempotencyStore
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JpaJournalIdempotencyStore(
    private val repository: JournalIdempotencyRecordJpaRepository,
) : JournalIdempotencyStore {

    override fun find(
        owner: IdentityUserId,
        operation: String,
        idempotencyKey: String,
    ): JournalIdempotencyRecord? = repository
        .findByIdentityUserIdAndOperationAndIdempotencyKey(owner.value, operation, idempotencyKey)
        ?.let { record ->
            JournalIdempotencyRecord(
                requestFingerprint = record.requestFingerprint,
                journalId = record.journalId,
            )
        }

    override fun save(
        owner: IdentityUserId,
        operation: String,
        idempotencyKey: String,
        requestFingerprint: String,
        journalId: UUID,
        createdAt: Instant,
    ) {
        repository.saveAndFlush(
            JournalIdempotencyRecordEntity(
                identityUserId = owner.value,
                operation = operation,
                idempotencyKey = idempotencyKey,
                requestFingerprint = requestFingerprint,
                journalId = journalId,
                createdAt = createdAt,
            ),
        )
    }
}
