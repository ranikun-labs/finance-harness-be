package labs.ranikun.finance.generalanalysis.application

import labs.ranikun.finance.generalanalysis.domain.ChecklistItem
import labs.ranikun.finance.generalanalysis.domain.ChecklistResult
import labs.ranikun.finance.generalanalysis.domain.FinanceQuestion
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisRecord
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.UUID

data class QuestionSetup(
    val questionId: UUID,
    val questionText: String,
    val checklistId: UUID,
    val checklistItems: List<ChecklistItem>,
    val checkedItemIds: Set<String>,
    val confirmed: Boolean,
)

data class GeneralAnalysisView(
    val analysisId: UUID,
    val status: GeneralAnalysisStatus,
    val output: GeneralAnalysisCandidate?,
)

class QuestionNotFoundException : NoSuchElementException("Question not found.")
class ChecklistNotFoundException : NoSuchElementException("Checklist not found.")
class AnalysisNotFoundException : NoSuchElementException("Analysis not found.")
class ChecklistNotConfirmedException : IllegalStateException("Checklist confirmation is required.")
class ChecklistQuestionMismatchException : IllegalArgumentException("Checklist does not belong to the question.")
class InvalidChecklistSelectionException : IllegalArgumentException("Checklist selection is invalid.")

@Service
@Profile("!prod & !production & (local | test)")
class GeneralAnalysisApplicationService(
    private val currentUserPort: CurrentUserPort,
    private val store: GeneralAnalysisStore,
    private val policyGuard: GeneralAnalysisPolicyGuard,
    private val lensRouter: GeneralAnalysisLensRouter,
    private val executionPort: GeneralAnalysisExecutionPort,
    private val outputValidator: GeneralAnalysisOutputValidator,
) {
    fun createQuestion(questionText: String): QuestionSetup {
        val owner = currentUserPort.currentUserId()
        val checklistId = UUID.randomUUID()
        val question = FinanceQuestion(UUID.randomUUID(), checklistId, owner, questionText)
        val checklist = ChecklistResult(
            id = checklistId,
            owner = owner,
            questionId = question.id,
            checkedItemIds = emptySet(),
            confirmed = false,
        )
        store.saveQuestion(question)
        store.saveChecklist(checklist)
        return QuestionSetup(
            questionId = question.id,
            questionText = question.text,
            checklistId = checklist.id,
            checklistItems = CHECKLIST_ITEMS,
            checkedItemIds = checklist.checkedItemIds,
            confirmed = checklist.confirmed,
        )
    }

    fun findQuestion(questionId: UUID): QuestionSetup {
        val owner = currentUserPort.currentUserId()
        val question = store.findQuestion(owner, questionId) ?: throw QuestionNotFoundException()
        val checklist = store.findChecklist(owner, question.checklistId) ?: throw ChecklistNotFoundException()
        return QuestionSetup(
            questionId = question.id,
            questionText = question.text,
            checklistId = checklist.id,
            checklistItems = CHECKLIST_ITEMS,
            checkedItemIds = checklist.checkedItemIds,
            confirmed = checklist.confirmed,
        )
    }

    @Synchronized
    fun confirmChecklist(
        questionId: UUID,
        checklistId: UUID,
        checkedItemIds: Set<String>,
        confirmed: Boolean,
    ): ChecklistResult {
        val owner = currentUserPort.currentUserId()
        val question = store.findQuestion(owner, questionId) ?: throw QuestionNotFoundException()
        val checklist = store.findChecklist(owner, checklistId) ?: throw ChecklistNotFoundException()
        if (checklist.questionId != question.id) throw ChecklistQuestionMismatchException()
        if (checkedItemIds.any { selected -> CHECKLIST_ITEMS.none { it.id == selected } }) {
            throw InvalidChecklistSelectionException()
        }
        val updated = checklist.copy(checkedItemIds = checkedItemIds, confirmed = confirmed, revision = checklist.revision + 1)
        store.saveChecklist(updated)
        return updated
    }

    fun analyze(questionId: UUID, checklistId: UUID, expectedRevision: Long? = null): GeneralAnalysisView {
        val owner = currentUserPort.currentUserId()
        val question = store.findQuestion(owner, questionId) ?: throw QuestionNotFoundException()
        val checklist = store.findChecklist(owner, checklistId) ?: throw ChecklistNotFoundException()
        if (checklist.questionId != question.id) throw ChecklistQuestionMismatchException()
        if (!checklist.confirmed || (expectedRevision != null && checklist.revision != expectedRevision)) {
            throw ChecklistNotConfirmedException()
        }

        var analysis = GeneralAnalysisRecord(
            id = UUID.randomUUID(),
            owner = owner,
            questionId = question.id,
            status = GeneralAnalysisStatus.REQUESTED,
            checklistId = checklist.id,
            checkedItemIds = checklist.checkedItemIds.toSet(),
            checklistRevision = checklist.revision,
        )
        store.saveAnalysis(analysis)

        // Policy classification must finish before lens selection or execution.
        val classification = try {
            policyGuard.classify(question.text)
        } catch (_: Exception) {
            analysis = analysis.copy(status = GeneralAnalysisStatus.FAILED)
            store.saveAnalysis(analysis)
            return analysis.toView()
        }
        if (classification != PolicyClassification.SAFE_GENERAL) {
            analysis = analysis.copy(status = GeneralAnalysisStatus.BLOCKED)
            store.saveAnalysis(analysis)
            return analysis.toView()
        }

        val lens = try {
            lensRouter.route(classification)
        } catch (_: Exception) {
            analysis = analysis.copy(status = GeneralAnalysisStatus.FAILED)
            store.saveAnalysis(analysis)
            return analysis.toView()
        }
        // 시작 확정: 체크리스트 변경과 같은 monitor에서 재확인하고, 바뀌었으면 모델을 호출하지 않는다.
        analysis = startExecution(analysis)
        if (analysis.status != GeneralAnalysisStatus.RUNNING) return analysis.toView()

        val executionResult = try {
            executionPort.execute(
                GeneralAnalysisExecutionInput(
                    owner = owner,
                    questionId = question.id,
                    checklistId = checklist.id,
                    analysisRequestId = analysis.id,
                    question = question.text,
                    checkedItemIds = checklist.checkedItemIds,
                    lens = lens,
                ),
            )
        } catch (_: Exception) {
            GeneralAnalysisExecutionResult.TechnicalFailure
        }

        analysis = when (executionResult) {
            GeneralAnalysisExecutionResult.TechnicalFailure -> analysis.copy(
                status = GeneralAnalysisStatus.FAILED,
                output = null,
            )

            is GeneralAnalysisExecutionResult.Success -> if (isValid(executionResult.candidate)) {
                analysis.copy(
                    status = GeneralAnalysisStatus.COMPLETED,
                    output = executionResult.candidate,
                )
            } else {
                analysis.copy(status = GeneralAnalysisStatus.FAILED, output = null)
            }
        }
        return finishAnalysis(analysis).toView()
    }

    // Use the same monitor as checklist updates in this process-local service.
    @Synchronized
    private fun finishAnalysis(analysis: GeneralAnalysisRecord): GeneralAnalysisRecord {
        val current = withoutStaleOutput(analysis)
        store.saveAnalysis(current)
        return current
    }

    @Synchronized
    private fun startExecution(analysis: GeneralAnalysisRecord): GeneralAnalysisRecord {
        val next = if (matchesCurrentChecklist(analysis)) {
            analysis.copy(status = GeneralAnalysisStatus.RUNNING)
        } else {
            analysis.copy(status = GeneralAnalysisStatus.STALE, output = null)
        }
        store.saveAnalysis(next)
        return next
    }

    @Synchronized
    fun findAnalysis(analysisId: UUID): GeneralAnalysisView {
        val owner = currentUserPort.currentUserId()
        val analysis = store.findAnalysis(owner, analysisId) ?: throw AnalysisNotFoundException()
        return withoutStaleOutput(analysis).toView()
    }

    private fun withoutStaleOutput(analysis: GeneralAnalysisRecord): GeneralAnalysisRecord {
        if (analysis.status != GeneralAnalysisStatus.COMPLETED) return analysis
        if (matchesCurrentChecklist(analysis)) return analysis
        return analysis.copy(status = GeneralAnalysisStatus.STALE, output = null)
    }

    private fun matchesCurrentChecklist(analysis: GeneralAnalysisRecord): Boolean {
        val checklist = analysis.checklistId?.let { store.findChecklist(analysis.owner, it) }
        return checklist != null && checklist.confirmed && checklist.questionId == analysis.questionId &&
            checklist.revision == analysis.checklistRevision && checklist.checkedItemIds == analysis.checkedItemIds
    }

    private fun isValid(candidate: GeneralAnalysisCandidate): Boolean = try {
        outputValidator.isValid(candidate)
    } catch (_: Exception) {
        false
    }

    private fun GeneralAnalysisRecord.toView() = GeneralAnalysisView(id, status, output)

    private companion object {
        val CHECKLIST_ITEMS = listOf(
            ChecklistItem("known_facts", "Which facts are present in the question?"),
            ChecklistItem("assumptions", "Which assumptions may affect the analysis?"),
            ChecklistItem("missing_context", "What relevant context is missing?"),
        )
    }
}
