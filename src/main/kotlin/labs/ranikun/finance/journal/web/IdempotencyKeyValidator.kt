package labs.ranikun.finance.journal.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class IdempotencyKeyValidator {

    fun validate(request: HttpServletRequest): String {
        val headers = request.getHeaders(HEADER_NAME)
        val values = generateSequence {
            if (headers.hasMoreElements()) headers.nextElement() else null
        }.toList()
        return validate(values)
    }

    fun validate(values: List<String>): String {
        if (values.isEmpty()) {
            throw invalid("required")
        }
        if (values.size != 1) {
            throw invalid("invalid")
        }

        val value = values.single()
        if (value.isBlank()) {
            throw invalid("required")
        }
        if (value.length > MAX_LENGTH) {
            throw invalid("too_long")
        }
        if (!KEY_PATTERN.matches(value)) {
            throw invalid("invalid")
        }
        return value
    }

    private fun invalid(code: String): InvalidJournalRequestException =
        InvalidJournalRequestException(listOf(JournalFieldError(HEADER_NAME, code)))

    companion object {
        const val HEADER_NAME = "Idempotency-Key"
        private const val MAX_LENGTH = 128
        private val KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$")
    }
}
