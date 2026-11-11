package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisStore
import labs.ranikun.finance.generalanalysis.domain.ChecklistResult
import labs.ranikun.finance.generalanalysis.domain.FinanceQuestion
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisRecord
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-local first-slice store; it is intentionally not a persistence contract. */
@Repository
@Profile("!prod & !production & (local | test)")
class InMemoryGeneralAnalysisStore : GeneralAnalysisStore {
    private val questions = ConcurrentHashMap<UUID, FinanceQuestion>()
    private val checklists = ConcurrentHashMap<UUID, ChecklistResult>()
    private val analyses = ConcurrentHashMap<UUID, GeneralAnalysisRecord>()

    override fun saveQuestion(question: FinanceQuestion) {
        questions[question.id] = question
    }

    override fun findQuestion(owner: IdentityUserId, questionId: UUID): FinanceQuestion? =
        questions[questionId]?.takeIf { it.owner == owner }

    override fun saveChecklist(checklist: ChecklistResult) {
        checklists[checklist.id] = checklist
    }

    override fun findChecklist(owner: IdentityUserId, checklistId: UUID): ChecklistResult? =
        checklists[checklistId]?.takeIf { it.owner == owner }

    override fun saveAnalysis(analysis: GeneralAnalysisRecord) {
        analyses[analysis.id] = analysis
    }

    override fun findAnalysis(owner: IdentityUserId, analysisId: UUID): GeneralAnalysisRecord? =
        analyses[analysisId]?.takeIf { it.owner == owner }
}
