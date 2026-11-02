package labs.ranikun.finance.generalanalysis.web

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisApplicationService
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisView
import labs.ranikun.finance.generalanalysis.application.QuestionSetup
import labs.ranikun.finance.generalanalysis.domain.ChecklistItem
import labs.ranikun.finance.generalanalysis.domain.ChecklistResult
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

data class CreateFinanceQuestionRequest(
    @field:NotBlank @field:Size(max = 2_000) val question: String,
)

data class CreateFinanceQuestionResponse(
    val questionId: String,
    val question: String,
    val checklistId: String,
    val checklistItems: List<ChecklistItem>,
    val checkedItemIds: Set<String>,
    val confirmed: Boolean,
)

data class ConfirmFinanceChecklistRequest(
    val checkedItemIds: Set<String>,
    val confirmed: Boolean,
)

data class ConfirmFinanceChecklistResponse(
    val checklistId: String,
    val questionId: String,
    val checkedItemIds: Set<String>,
    val confirmed: Boolean,
)

data class StartGeneralAnalysisRequest(val checklistId: String)

data class GeneralAnalysisResponse(
    val analysisId: String,
    val status: GeneralAnalysisStatus,
    val output: GeneralAnalysisCandidate?,
)

data class GeneralAnalysisErrorResponse(val code: String, val message: String)

class InvalidGeneralAnalysisRequestException : IllegalArgumentException("Invalid general analysis request.")

@RestController
@Profile("!prod & !production & (local | test)")
@RequestMapping("/finance/general-analysis")
class GeneralAnalysisController(
    private val service: GeneralAnalysisApplicationService,
) {
    @PostMapping("/questions")
    fun createQuestion(@Valid @RequestBody request: CreateFinanceQuestionRequest): ResponseEntity<CreateFinanceQuestionResponse> {
        val setup = service.createQuestion(request.question)
        val response = setup.toResponse()
        return ResponseEntity.created(URI.create("/finance/general-analysis/questions/${response.questionId}"))
            .body(response)
    }

    @GetMapping("/questions/{questionId}")
    fun getQuestion(@PathVariable questionId: String): CreateFinanceQuestionResponse =
        service.findQuestion(parseId(questionId)).toResponse()

    @PutMapping("/questions/{questionId}/checklists/{checklistId}")
    fun confirmChecklist(
        @PathVariable questionId: String,
        @PathVariable checklistId: String,
        @Valid @RequestBody request: ConfirmFinanceChecklistRequest,
    ): ConfirmFinanceChecklistResponse = service.confirmChecklist(
        parseId(questionId),
        parseId(checklistId),
        request.checkedItemIds,
        request.confirmed,
    ).toResponse()

    @PostMapping("/questions/{questionId}/analysis")
    fun analyze(
        @PathVariable questionId: String,
        @RequestBody request: StartGeneralAnalysisRequest,
    ): GeneralAnalysisResponse = service.analyze(parseId(questionId), parseId(request.checklistId)).toResponse()

    @GetMapping("/analyses/{analysisId}")
    fun getAnalysis(@PathVariable analysisId: String): GeneralAnalysisResponse =
        service.findAnalysis(parseId(analysisId)).toResponse()

    private fun parseId(raw: String): UUID = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw InvalidGeneralAnalysisRequestException()
    }

    private fun QuestionSetup.toResponse() = CreateFinanceQuestionResponse(
        questionId = questionId.toString(),
        question = questionText,
        checklistId = checklistId.toString(),
        checklistItems = checklistItems,
        checkedItemIds = checkedItemIds,
        confirmed = confirmed,
    )

    private fun ChecklistResult.toResponse() = ConfirmFinanceChecklistResponse(
        checklistId = id.toString(),
        questionId = questionId.toString(),
        checkedItemIds = checkedItemIds,
        confirmed = confirmed,
    )

    private fun GeneralAnalysisView.toResponse() = GeneralAnalysisResponse(
        analysisId = analysisId.toString(),
        status = status,
        output = output,
    )
}
