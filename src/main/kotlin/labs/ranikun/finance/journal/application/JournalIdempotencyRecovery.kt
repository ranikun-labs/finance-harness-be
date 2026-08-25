package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JournalIdempotencyRecovery(
    private val idempotencyStore: JournalIdempotencyStore,
) {

    @Transactional(readOnly = true)
    fun find(owner: IdentityUserId, idempotencyKey: String): JournalIdempotencyRecord? =
        idempotencyStore.find(owner, JOURNAL_CREATE_OPERATION, idempotencyKey)
}
