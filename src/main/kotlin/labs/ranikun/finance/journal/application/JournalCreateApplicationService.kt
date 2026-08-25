package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

class IdempotencyWinnerNotFoundException(cause: Throwable) : IllegalStateException(
    "Namespace unique violation had no committed idempotency winner",
    cause,
)

@Service
class JournalCreateApplicationService(
    private val currentUserPort: CurrentUserPort,
    private val journalCreateFingerprint: JournalCreateFingerprint,
    private val journalCreateTransactionalAttempt: JournalCreateTransactionalAttempt,
    private val journalIdempotencyRecovery: JournalIdempotencyRecovery,
    private val constraintViolationDetector: JournalIdempotencyConstraintViolationDetector,
) {

    fun create(command: JournalCreateCommand, idempotencyKey: String): UUID {
        val owner = currentUserPort.currentUserId()
        val requestFingerprint = journalCreateFingerprint.calculate(command)

        return try {
            journalCreateTransactionalAttempt.execute(
                owner = owner,
                idempotencyKey = idempotencyKey,
                requestFingerprint = requestFingerprint,
                command = command,
            )
        } catch (exception: DataIntegrityViolationException) {
            if (!constraintViolationDetector.isNamespaceUniqueViolation(exception)) {
                throw exception
            }

            val winner = journalIdempotencyRecovery.find(owner, idempotencyKey)
                ?: throw IdempotencyWinnerNotFoundException(exception)
            if (winner.requestFingerprint != requestFingerprint) {
                throw IdempotencyKeyReusedException()
            }
            winner.journalId
        }
    }
}
