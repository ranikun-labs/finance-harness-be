package labs.ranikun.finance.generalanalysis.web

import labs.ranikun.finance.generalanalysis.application.AnalysisNotFoundException
import labs.ranikun.finance.generalanalysis.application.ChecklistNotConfirmedException
import labs.ranikun.finance.generalanalysis.application.ChecklistNotFoundException
import labs.ranikun.finance.generalanalysis.application.ChecklistQuestionMismatchException
import labs.ranikun.finance.generalanalysis.application.InvalidChecklistSelectionException
import labs.ranikun.finance.generalanalysis.application.QuestionNotFoundException
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [GeneralAnalysisController::class])
@Profile("!prod & !production & (local | test)")
class GeneralAnalysisApiExceptionHandler {
    @ExceptionHandler(
        QuestionNotFoundException::class,
        ChecklistNotFoundException::class,
        AnalysisNotFoundException::class,
    )
    fun notFound(): ResponseEntity<GeneralAnalysisErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            GeneralAnalysisErrorResponse("not_found", "Requested resource was not found."),
        )

    @ExceptionHandler(ChecklistNotConfirmedException::class, ChecklistQuestionMismatchException::class)
    fun checklistNotReady(): ResponseEntity<GeneralAnalysisErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            GeneralAnalysisErrorResponse("checklist_not_ready", "A confirmed checklist for this question is required."),
        )

    @ExceptionHandler(InvalidChecklistSelectionException::class, InvalidGeneralAnalysisRequestException::class)
    fun invalidRequest(): ResponseEntity<GeneralAnalysisErrorResponse> =
        ResponseEntity.badRequest().body(
            GeneralAnalysisErrorResponse("invalid_request", "Request is invalid."),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun invalidBody(): ResponseEntity<GeneralAnalysisErrorResponse> = invalidRequest()
}
