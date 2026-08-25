package labs.ranikun.finance.journal.application

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

@Component
class JournalIdempotencyConstraintViolationDetector {

    fun isNamespaceUniqueViolation(exception: DataIntegrityViolationException): Boolean =
        generateSequence(exception as Throwable) { it.cause }
            .mapNotNull { throwable ->
                if (throwable is ConstraintViolationException) {
                    val constraintName: String? = throwable.constraintName
                    constraintName
                } else {
                    null
                }
            }
            .any { constraintName -> constraintName == NAMESPACE_CONSTRAINT }

    companion object {
        const val NAMESPACE_CONSTRAINT = "uq_journal_idempotency_records_namespace"
    }
}
