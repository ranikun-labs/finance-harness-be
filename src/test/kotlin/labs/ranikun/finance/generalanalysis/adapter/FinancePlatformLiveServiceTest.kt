package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisApplicationService
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class FinancePlatformLiveServiceTest {
    @Test
    fun `actual platform response passes finance adapter and output validator`() {
        val service = GeneralAnalysisApplicationService(
            currentUserPort = object : CurrentUserPort { override fun currentUserId() = IdentityUserId("test-owner") },
            store = InMemoryGeneralAnalysisStore(),
            policyGuard = FinanceGeneralAnalysisPolicyGuard(),
            lensRouter = SafeGeneralAnalysisLensRouter(),
            executionPort = FinanceGeneralAnalysisExecutionAdapter(
                LocalFinanceExecutionContextFactory("tenant:integration-test"),
                FinanceSharedAiHttpClient(URI.create(requireNotNull(System.getenv("FINANCE_SHARED_AI_TEST_URL")))),
            ),
            outputValidator = FinanceGeneralAnalysisOutputValidator(),
        )
        val question = service.createQuestion("How can I verify these facts?")
        service.confirmChecklist(question.questionId, question.checklistId, setOf("known_facts"), true)
        val result = service.analyze(question.questionId, question.checklistId)
        assertThat(result.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(result.output).isNotNull()
    }
}
