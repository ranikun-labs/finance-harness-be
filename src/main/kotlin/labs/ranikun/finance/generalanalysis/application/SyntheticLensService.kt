package labs.ranikun.finance.generalanalysis.application

import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.UUID
import java.time.Instant
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class SyntheticScenario { SECTOR, MACRO, WEAK }
enum class SyntheticNextLens { SECTOR, MACRO }
data class SyntheticFact(val id: String, val source: String, val value: String)
data class SyntheticBasic(val sector: Double, val macro: Double, val facts: List<SyntheticFact>, val snapshotId: String, val asOf: Instant, val expiresAt: Instant)
data class SyntheticSupplement(val snapshotId: String, val facts: List<SyntheticFact>)
data class SyntheticLensResult(val status: String, val facts: List<SyntheticFact>, val interpretation: String?, val nextLens: SyntheticNextLens?, val basicCalls: Int, val supplementalCalls: Int, val asOf: Instant?, val fixtureLabel: String)
interface SyntheticLookup {
    fun basic(scenario: SyntheticScenario): SyntheticBasic?
    fun supplemental(scenario: SyntheticScenario, lens: SyntheticNextLens, snapshotId: String): SyntheticSupplement?
}

@Service
@Profile("!prod & !production & (local | test)")
@ConditionalOnProperty(prefix = "finance.shared-ai", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class SyntheticLensService(private val currentUser: CurrentUserPort, private val store: GeneralAnalysisStore, private val policy: GeneralAnalysisPolicyGuard, private val lookup: SyntheticLookup) {
    fun run(questionId: UUID, checklistId: UUID, analysisId: UUID, scenario: SyntheticScenario): SyntheticLensResult {
        val owner = currentUser.currentUserId()
        val question = store.findQuestion(owner, questionId) ?: throw QuestionNotFoundException()
        val checklist = store.findChecklist(owner, checklistId) ?: throw ChecklistNotFoundException()
        if (checklist.questionId != question.id) throw ChecklistQuestionMismatchException()
        if (!checklist.confirmed) throw ChecklistNotConfirmedException()
        if (try { policy.classify(question.text) != PolicyClassification.SAFE_GENERAL } catch (_: Exception) { true }) return failed("BLOCKED")
        val analysis = store.findAnalysis(owner, analysisId) ?: throw AnalysisNotFoundException()
        if (analysis.questionId != questionId || analysis.status != GeneralAnalysisStatus.COMPLETED || analysis.output == null || analysis.checklistId != checklistId || analysis.checkedItemIds != checklist.checkedItemIds || analysis.checklistRevision != checklist.revision) throw ChecklistQuestionMismatchException()
        var basicCalls = 0
        var supplementalCalls = 0
        fun unchanged(): Boolean = store.findChecklist(owner, checklistId)?.let { it.questionId == questionId && it.confirmed && it.checkedItemIds == analysis.checkedItemIds && it.revision == analysis.checklistRevision } == true
        return try {
            if (!unchanged()) return failed("FAILED", basicCalls, supplementalCalls)
            basicCalls++
            val basic = bounded { lookup.basic(scenario) } ?: return failed("FAILED", basicCalls, supplementalCalls)
            if (!validBasic(basic, scenario) || !unchanged()) return failed("FAILED", basicCalls, supplementalCalls)
            val lens = if (basic.sector < 0.6 && basic.macro < 0.6) null else if (basic.sector >= basic.macro) SyntheticNextLens.SECTOR else SyntheticNextLens.MACRO
            val extra = if (lens == null) emptyList() else {
                supplementalCalls++
                val supplement = bounded { lookup.supplemental(scenario, lens, basic.snapshotId) } ?: return failed("FAILED", basicCalls, supplementalCalls)
                if (supplement.snapshotId != basic.snapshotId || !validSupplement(supplement.facts, scenario, lens, basic.facts)) return failed("FAILED", basicCalls, supplementalCalls)
                supplement.facts
            }
            if (!unchanged() || !validBasic(basic, scenario)) return failed("FAILED", basicCalls, supplementalCalls)
            SyntheticLensResult("COMPLETED", basic.facts + extra, when (lens) { SyntheticNextLens.SECTOR -> "Sector concentration is the stronger or tied synthetic signal."; SyntheticNextLens.MACRO -> "Broad macro is the stronger synthetic signal."; null -> "Both synthetic signals are weak; no further lens." }, lens, basicCalls, supplementalCalls, basic.asOf, "SYNTHETIC_FIXTURE")
        } catch (_: Exception) { failed("FAILED", basicCalls, supplementalCalls) }
    }
    private fun validBasic(b: SyntheticBasic, scenario: SyntheticScenario): Boolean = b.snapshotId.isNotBlank() && b.snapshotId.length <= 100 && !b.asOf.isAfter(Instant.now()) && b.expiresAt.isAfter(Instant.now()) && Duration.between(b.asOf, b.expiresAt) <= Duration.ofMinutes(5) && (b.sector to b.macro) == when (scenario) { SyntheticScenario.SECTOR -> 0.8 to 0.4; SyntheticScenario.MACRO -> 0.3 to 0.9; SyntheticScenario.WEAK -> 0.2 to 0.3 } && b.sector.isFinite() && b.macro.isFinite() && b.sector in 0.0..1.0 && b.macro in 0.0..1.0 && b.facts.size == 2 && b.facts.map { it.id }.toSet() == setOf("sector", "macro") && b.facts.all { it.source == "synthetic-basic:${scenario.name}" && it.value.isNotBlank() && it.value.length <= 100 } && b.facts.single { it.id == "sector" }.value == b.sector.toString() && b.facts.single { it.id == "macro" }.value == b.macro.toString()
    private fun validSupplement(facts: List<SyntheticFact>, scenario: SyntheticScenario, lens: SyntheticNextLens, basic: List<SyntheticFact>): Boolean = facts.size == 1 && facts[0].id == "supplement:${lens.name}" && facts[0].source == "synthetic-supplement:${scenario.name}:${lens.name}" && facts[0].value == when (lens) { SyntheticNextLens.SECTOR -> "Invented sector concentration detail"; SyntheticNextLens.MACRO -> "Invented macro context detail" } && basic.none { it.id == facts[0].id }
    private fun <T> bounded(work: () -> T): T {
        val future = LOOKUP_EXECUTOR.submit(Callable { work() })
        return try { future.get(LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) } finally { future.cancel(true) }
    }
    private companion object {
        const val LOOKUP_TIMEOUT_MILLIS = 1000L
        // A saturated or unresponsive synthetic lookup fails closed without queue growth.
        val LOOKUP_EXECUTOR = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(), { task -> Thread(task, "synthetic-lookup").apply { isDaemon = true } })
    }
    private fun failed(status: String, basicCalls: Int = 0, supplementalCalls: Int = 0) = SyntheticLensResult(status, emptyList(), null, null, basicCalls, supplementalCalls, null, "SYNTHETIC_FIXTURE")
}
