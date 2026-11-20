package labs.ranikun.finance.generalanalysis.application

import labs.ranikun.finance.generalanalysis.adapter.InMemoryGeneralAnalysisStore
import labs.ranikun.finance.generalanalysis.adapter.InMemorySyntheticLookup
import labs.ranikun.finance.generalanalysis.domain.*
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SyntheticLensServiceTests {
    private val owner = IdentityUserId("synthetic-test")
    private val current = object : CurrentUserPort { override fun currentUserId() = owner }
    private val store = InMemoryGeneralAnalysisStore()
    private val q = UUID.randomUUID(); private val c = UUID.randomUUID(); private val a = UUID.randomUUID()
    private val candidate = GeneralAnalysisCandidate("summary", listOf("observation"), listOf("assumption"), listOf("check"), "evidence")
    private val policy = object : GeneralAnalysisPolicyGuard { var result = PolicyClassification.SAFE_GENERAL; override fun classify(question: String) = result }
    private val actual = InMemorySyntheticLookup()
    private val lookup = object : SyntheticLookup {
        var basicCalls = 0; var extraCalls = 0; var mode = "ok"
        override fun basic(scenario: SyntheticScenario): SyntheticBasic? { basicCalls++; if (mode == "timeout") Thread.sleep(5000); if (mode == "missing") return null; val b = actual.basic(scenario); if (mode == "stale") store.saveChecklist(ChecklistResult(c, owner, q, setOf("known_facts"), true, 2)); return when (mode) { "expired" -> b.copy(asOf = Instant.now().minusSeconds(120), expiresAt = Instant.now().minusSeconds(1)); "bad-source" -> b.copy(facts = b.facts.map { it.copy(source = "other") }); else -> b } }
        override fun supplemental(scenario: SyntheticScenario, lens: SyntheticNextLens, snapshotId: String): SyntheticSupplement? { extraCalls++; val e = actual.supplemental(scenario, lens, snapshotId); return if (mode == "mismatch") e.copy(snapshotId = "other") else e }
    }
    private val service = SyntheticLensService(current, store, policy, lookup)
    init { store.saveQuestion(FinanceQuestion(q, c, owner, "What is risk?")); store.saveChecklist(ChecklistResult(c, owner, q, setOf("known_facts"), true, 1)); store.saveAnalysis(GeneralAnalysisRecord(a, owner, q, GeneralAnalysisStatus.COMPLETED, candidate, c, setOf("known_facts"), 1)) }
    @Test fun `three scenarios execute bounded lookups`() {
        for ((scenario, lens, count) in listOf(Triple(SyntheticScenario.SECTOR, SyntheticNextLens.SECTOR, 1), Triple(SyntheticScenario.MACRO, SyntheticNextLens.MACRO, 1), Triple(SyntheticScenario.WEAK, null, 0))) {
            val result = service.run(q,c,a,scenario); assertThat(result.status).isEqualTo("COMPLETED"); assertThat(result.nextLens).isEqualTo(lens); assertThat(result.supplementalCalls).isEqualTo(count); assertThat(result.fixtureLabel).isEqualTo("SYNTHETIC_FIXTURE"); assertThat(result.asOf).isNotNull()
        }
    }
    @Test fun `denied policy and conflict call no lookup`() {
        policy.result = PolicyClassification.RISK_DECISION; assertThat(service.run(q,c,a,SyntheticScenario.SECTOR).status).isEqualTo("BLOCKED"); assertThat(lookup.basicCalls).isZero(); assertThat(lookup.extraCalls).isZero()
        policy.result = PolicyClassification.SAFE_GENERAL; store.saveChecklist(ChecklistResult(c, owner, q, setOf("known_facts"), true, 2)); assertThatThrownBy { service.run(q,c,a,SyntheticScenario.SECTOR) }.isInstanceOf(ChecklistQuestionMismatchException::class.java); assertThat(lookup.basicCalls).isZero()
    }
    @Test fun `missing timeout expired and mismatched evidence fail closed`() {
        for (mode in listOf("missing", "timeout", "expired", "bad-source", "mismatch", "stale")) { val start = System.nanoTime(); lookup.mode = mode; lookup.basicCalls = 0; lookup.extraCalls = 0; val result = service.run(q,c,a,SyntheticScenario.SECTOR); assertThat(result.status).isEqualTo("FAILED"); if (mode == "timeout") assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3000); assertThat(result.facts).isEmpty(); assertThat(lookup.extraCalls).isEqualTo(if (mode == "mismatch") 1 else 0) }
    }
    @Test fun `sector suggestion is single use and invalidated by checklist revision`() {
        assertThat(service.run(q, c, a, SyntheticScenario.MACRO).suggestionId).isNull()
        val id = service.run(q, c, a, SyntheticScenario.SECTOR).suggestionId!!
        assertThat(service.contribution(q, c, a, id).values.map { it.identifier }).containsExactly("SYNTHETIC-A", "SYNTHETIC-B")
        assertThat(service.contribution(q, c, a, id).status).isEqualTo("FAILED")
        val stale = service.run(q, c, a, SyntheticScenario.SECTOR).suggestionId!!
        store.saveChecklist(ChecklistResult(c, owner, q, setOf("known_facts"), true, 2))
        assertThat(service.contribution(q, c, a, stale).status).isEqualTo("FAILED")
    }
}
