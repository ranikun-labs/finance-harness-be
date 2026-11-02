package labs.ranikun.finance.generalanalysis.application

import labs.ranikun.finance.generalanalysis.domain.FinanceQuestion
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisRecord
import labs.ranikun.finance.generalanalysis.domain.ChecklistResult
import labs.ranikun.finance.identity.application.IdentityUserId
import java.util.UUID

interface GeneralAnalysisStore {
    fun saveQuestion(question: FinanceQuestion)
    fun findQuestion(owner: IdentityUserId, questionId: UUID): FinanceQuestion?
    fun saveChecklist(checklist: ChecklistResult)
    fun findChecklist(owner: IdentityUserId, checklistId: UUID): ChecklistResult?
    fun saveAnalysis(analysis: GeneralAnalysisRecord)
    fun findAnalysis(owner: IdentityUserId, analysisId: UUID): GeneralAnalysisRecord?
}

enum class PolicyClassification {
    SAFE_GENERAL,
    RISK_DECISION,
    UNKNOWN,
}

interface GeneralAnalysisPolicyGuard {
    fun classify(question: String): PolicyClassification
}

enum class GeneralAnalysisLens {
    GENERAL_ANALYSIS,
}

interface GeneralAnalysisLensRouter {
    fun route(classification: PolicyClassification): GeneralAnalysisLens
}

data class GeneralAnalysisExecutionInput(
    val owner: IdentityUserId,
    val questionId: UUID,
    val checklistId: UUID,
    val analysisRequestId: UUID,
    val question: String,
    val checkedItemIds: Set<String>,
    val lens: GeneralAnalysisLens,
)

sealed interface GeneralAnalysisExecutionResult {
    data class Success(val candidate: GeneralAnalysisCandidate) : GeneralAnalysisExecutionResult
    data object TechnicalFailure : GeneralAnalysisExecutionResult
}

interface GeneralAnalysisExecutionPort {
    fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult
}

interface GeneralAnalysisOutputValidator {
    fun isValid(candidate: GeneralAnalysisCandidate): Boolean
}
