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

    /** null이면 통과. 실패 시 고정 규칙 이름만 돌려주며, 로그 detail로만 쓴다. */
    fun rejectionRule(candidate: GeneralAnalysisCandidate): OutputRejectionRule? =
        if (isValid(candidate)) null else OutputRejectionRule.UNCLASSIFIED
}

/** 출력 거절 규칙 식별자. 응답 내용은 담지 않고 어떤 규칙이 막았는지만 기록한다. */
enum class OutputRejectionRule {
    SUMMARY_OR_EVIDENCE_BLANK,
    EMPTY_ARRAY,
    FIELD_BLANK,
    FIELD_TOO_LONG,
    UNSUPPORTED_FINANCIAL_CONTENT,
    DIRECT_SHORT_SYMBOL_ACTION,
    DIRECT_BROKER_ACTION,
    VALIDATOR_EXCEPTION,
    UNCLASSIFIED,
}
