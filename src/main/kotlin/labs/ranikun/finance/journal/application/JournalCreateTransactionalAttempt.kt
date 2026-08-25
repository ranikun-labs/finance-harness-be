package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

const val JOURNAL_CREATE_OPERATION = "journal.create.v1"

class IdempotencyKeyReusedException : RuntimeException(
    "Idempotency key was already used for a different request.",
)

@Service
class JournalCreateTransactionalAttempt(
    private val journalIdGenerator: JournalIdGenerator,
    private val journalStore: JournalStore,
    private val idempotencyStore: JournalIdempotencyStore,
    private val clock: Clock,
) {

    @Transactional
    fun execute(
        owner: IdentityUserId,
        idempotencyKey: String,
        requestFingerprint: String,
        command: JournalCreateCommand,
    ): UUID {
        val existing = idempotencyStore.find(owner, JOURNAL_CREATE_OPERATION, idempotencyKey)
        if (existing != null) {
            if (existing.requestFingerprint == requestFingerprint) {
                return existing.journalId
            }
            throw IdempotencyKeyReusedException()
        }

        val journalId = journalIdGenerator.generate()
        journalStore.save(journalId, owner, command)
        idempotencyStore.save(
            owner = owner,
            operation = JOURNAL_CREATE_OPERATION,
            idempotencyKey = idempotencyKey,
            requestFingerprint = requestFingerprint,
            journalId = journalId,
            createdAt = Instant.now(clock),
        )
        return journalId
    }
}
