package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JournalIdempotencyRecovery(
    private val idempotencyStore: JournalIdempotencyStore,
    private val lifecycle: JournalLifecycleService,
) {

    @Transactional
    fun find(owner: IdentityUserId, idempotencyKey: String, fingerprint: String): JournalIdempotencyRecord? {
        val winner = idempotencyStore.find(owner, JOURNAL_CREATE_OPERATION, idempotencyKey) ?: return null
        if (winner.requestFingerprint != fingerprint) throw IdempotencyKeyReusedException()
        lifecycle.requireActiveForReplay(owner, winner.journalId)
        return winner
    }
}
