package labs.ranikun.finance.generalanalysis.web

import jakarta.validation.Valid
import labs.ranikun.finance.generalanalysis.application.SyntheticLensService
import labs.ranikun.finance.generalanalysis.application.SyntheticScenario
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.*

data class SyntheticLensRequest(val checklistId: String, val analysisId: String, val scenario: SyntheticScenario)

/** Separate local-only surface exists only when the deterministic execution adapter is selected. */
@RestController
@Profile("!prod & !production & (local | test)")
@ConditionalOnProperty(prefix = "finance.shared-ai", name = ["enabled"], havingValue = "false", matchIfMissing = true)
@RequestMapping("/finance/local-fake-analysis")
class LocalFakeGeneralAnalysisController(private val delegate: GeneralAnalysisController, private val syntheticLens: SyntheticLensService) {
    @PostMapping("/questions/{questionId}/synthetic-lens")
    fun syntheticLens(@PathVariable questionId: String, @RequestBody request: SyntheticLensRequest) = syntheticLens.run(parseId(questionId), parseId(request.checklistId), parseId(request.analysisId), request.scenario)

    private fun parseId(value: String) = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw InvalidGeneralAnalysisRequestException() }

    @PostMapping("/questions")
    fun createQuestion(@Valid @RequestBody request: CreateFinanceQuestionRequest) = delegate.createQuestion(request)

    @GetMapping("/questions/{questionId}")
    fun getQuestion(@PathVariable questionId: String) = delegate.getQuestion(questionId)

    @PutMapping("/questions/{questionId}/checklists/{checklistId}")
    fun confirmChecklist(@PathVariable questionId: String, @PathVariable checklistId: String, @Valid @RequestBody request: ConfirmFinanceChecklistRequest) =
        delegate.confirmChecklist(questionId, checklistId, request)

    @PostMapping("/questions/{questionId}/analysis")
    fun analyze(@PathVariable questionId: String, @RequestBody request: StartGeneralAnalysisRequest) = delegate.analyze(questionId, request)
}
