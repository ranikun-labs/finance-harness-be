package labs.ranikun.finance.journal.web

import labs.ranikun.finance.common.web.RequestIdFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class JournalFieldError(
    val field: String,
    val code: String,
)

data class JournalErrorResponse(
    val code: String,
    val message: String,
    val requestId: String,
    val fieldErrors: List<JournalFieldError>,
)

class InvalidJournalRequestException(
    val fieldErrors: List<JournalFieldError>,
) : IllegalArgumentException("Invalid Journal request")

@RestControllerAdvice
class JournalApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<JournalErrorResponse> =
        invalidRequest(
            exception.bindingResult.fieldErrors.map { fieldError ->
                JournalFieldError(fieldError.field, validationCode(fieldError.code))
            },
        )

    @ExceptionHandler(InvalidJournalRequestException::class)
    fun handleJournalValidation(exception: InvalidJournalRequestException): ResponseEntity<JournalErrorResponse> =
        invalidRequest(exception.fieldErrors)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(): ResponseEntity<JournalErrorResponse> = invalidRequest(emptyList())

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<JournalErrorResponse> {
        val requestId = requestId()
        LOGGER.error(
            "journal_request_failed requestId={} exceptionType={}",
            requestId,
            exception::class.simpleName,
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            JournalErrorResponse(
                code = "internal_error",
                message = "An internal error occurred.",
                requestId = requestId,
                fieldErrors = emptyList(),
            ),
        )
    }

    private fun invalidRequest(fieldErrors: List<JournalFieldError>): ResponseEntity<JournalErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            JournalErrorResponse(
                code = "invalid_request",
                message = "Request is invalid.",
                requestId = requestId(),
                fieldErrors = fieldErrors,
            ),
        )

    private fun requestId(): String = MDC.get(RequestIdFilter.MDC_KEY) ?: "unknown"

    private fun validationCode(code: String?): String = when (code) {
        "NotBlank" -> "required"
        "Size" -> "too_long"
        else -> "invalid"
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(JournalApiExceptionHandler::class.java)
    }
}
