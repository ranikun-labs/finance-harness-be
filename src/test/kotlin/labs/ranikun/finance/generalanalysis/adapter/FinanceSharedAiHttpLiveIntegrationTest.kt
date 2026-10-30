package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.FinanceGeneralAnalysisBusinessInput
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiOutcome
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequest
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import java.net.URI

@EnabledIfEnvironmentVariable(named = "FINANCE_SHARED_AI_TEST_URL", matches = ".+")
class FinanceSharedAiHttpLiveIntegrationTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `Finance HTTP consumer receives a correlated Shared AI response`() {
        val context = FinanceSharedAiRequestContext(
            productReference = "finance",
            tenantReference = "tenant:integration-test",
            resourceReferences = setOf("question:integration-test", "checklist:integration-test"),
            principalReference = "principal:integration-test",
            requestReference = "request:integration-test",
            correlationReference = "correlation:integration-test",
            authorityReference = "authority:integration-test",
        )
        val businessInput = FinanceGeneralAnalysisBusinessInput(
            question = "Review synthetic notes for omitted checks.",
            checkedChecklistItemIds = listOf("assumptions", "known_facts", "missing_context"),
            lensRef = "general-analysis",
        )
        val request = FinanceSharedAiRequest(
            context = context,
            skillId = "finance.general-analysis@1",
            businessInput = businessInput,
            query = FinanceGeneralAnalysisQueryFormatter().format(businessInput),
        )

        val outcome = FinanceSharedAiHttpClient(URI.create(requireNotNull(System.getenv("FINANCE_SHARED_AI_TEST_URL"))))
            .execute(request)

        assertThat(outcome).isInstanceOf(FinanceSharedAiOutcome.Success::class.java)
        val success = outcome as FinanceSharedAiOutcome.Success
        assertThat(success.executionReference).isNotBlank()
        assertThat(success.interpretation).contains("current items")
        assertThat(success.evidence.context.executionReference).isEqualTo(success.executionReference)
        assertThat(success.evidence.context.contextVersion).isEqualTo("1")
        assertThat(success.evidence.context.productReference).isEqualTo("finance")
        assertThat(success.evidence.context.tenantReference).isEqualTo(context.tenantReference)
        assertThat(success.evidence.context.resourceReferences).isEqualTo(context.resourceReferences)
        assertThat(success.evidence.context.principalReference).isEqualTo(context.principalReference)
        assertThat(success.evidence.context.requestReference).isEqualTo(context.requestReference)
        assertThat(success.evidence.context.correlationReference).isEqualTo(context.correlationReference)
        assertThat(success.evidence.context.authorityReference).isEqualTo(context.authorityReference)
        assertThat(success.evidence.items.map { it.kind })
            .containsExactlyInAnyOrder("STRUCTURED_PRODUCT_FACT", "MODEL_INTERPRETATION")
        assertThat(success.evidence.items).allSatisfy {
            assertThat(it.executionReference).isEqualTo(success.executionReference)
            assertThat(it.scopeReference.productReference).isEqualTo(context.productReference)
            assertThat(it.scopeReference.tenantReference).isEqualTo(context.tenantReference)
            assertThat(it.scopeReference.resourceReferences).isEqualTo(context.resourceReferences)
            assertThat(it.sourceReferences).isNotEmpty()
            assertThat(it.producerReference).isNotBlank()
            assertThat(it.observedAt).isNotNull()
            assertThat(it.schemaVersion).isNotBlank()
        }
    }
}
