package labs.ranikun.finance.generalanalysis.domain

import labs.ranikun.finance.identity.application.IdentityUserId
import java.util.UUID

data class FinanceQuestion(
    val id: UUID,
    val checklistId: UUID,
    val owner: IdentityUserId,
    val text: String,
)

data class ChecklistItem(
    val id: String,
    val prompt: String,
)

data class ChecklistResult(
    val id: UUID,
    val owner: IdentityUserId,
    val questionId: UUID,
    val checkedItemIds: Set<String>,
    val confirmed: Boolean,
)

enum class GeneralAnalysisStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
    BLOCKED,
}

data class GeneralAnalysisCandidate(
    val summary: String,
    val observations: List<String>,
    val assumptions: List<String>,
    val nextChecks: List<String>,
    val evidenceNote: String,
)

data class GeneralAnalysisRecord(
    val id: UUID,
    val owner: IdentityUserId,
    val questionId: UUID,
    val status: GeneralAnalysisStatus,
    val output: GeneralAnalysisCandidate? = null,
)
