package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.IdentityUserId
import java.time.Instant
import java.util.UUID

data class JournalIdempotencyRecord(
    val requestFingerprint: String,
    val journalId: UUID,
)

interface JournalIdempotencyStore {

    fun find(
        owner: IdentityUserId,
        operation: String,
        idempotencyKey: String,
    ): JournalIdempotencyRecord?

    fun save(
        owner: IdentityUserId,
        operation: String,
        idempotencyKey: String,
        requestFingerprint: String,
        journalId: UUID,
        createdAt: Instant,
    )
}
