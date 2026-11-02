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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8

@EnabledIfEnvironmentVariable(named = "FINANCE_SHARED_AI_TEST_URL", matches = ".+")
class FinanceSharedAiHttpLiveFailureIntegrationTests {
    private val baseUrl = URI.create(requireNotNull(System.getenv("FINANCE_SHARED_AI_TEST_URL")))
    private val financeClient = FinanceSharedAiHttpClient(baseUrl)
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `unregistered skill returns a technical failure over HTTP`() {
        val response = postWireRequest(wireRequest(skillId = "finance.unregistered@1"))

        assertThat(response.statusCode()).isEqualTo(200)
        val failure = mapper.readTree(response.body())
        assertThat(failure.path("outcome").stringValue()).isEqualTo("technicalFailure")
        assertThat(failure.path("category").stringValue()).isEqualTo("UNSUPPORTED_SKILL")
        assertThat(failure.path("executionReference").stringValue()).isNotBlank()
    }

    @Test
    fun `repeating a request creates separate executions over HTTP`() {
        val request = request()

        val first = financeClient.execute(request)
        val second = financeClient.execute(request)

        assertThat(first).isInstanceOf(FinanceSharedAiOutcome.Success::class.java)
        assertThat(second).isInstanceOf(FinanceSharedAiOutcome.Success::class.java)
        assertThat((first as FinanceSharedAiOutcome.Success).executionReference)
            .isNotEqualTo((second as FinanceSharedAiOutcome.Success).executionReference)
    }

    @Test
    fun `malformed wire request receives a sanitized HTTP error`() {
        val response = postWireRequest("{")

        assertThat(response.statusCode()).isEqualTo(400)
        val error = mapper.readTree(response.body())
        assertThat(error.size()).isEqualTo(1)
        assertThat(error.path("error").stringValue()).isEqualTo("INVALID_REQUEST")
        assertThat(response.body().lowercase()).doesNotContain("exception", "stack", "trace", "message")
    }

    private fun request(skillId: String = "finance.general-analysis@1"): FinanceSharedAiRequest {
        val context = FinanceSharedAiRequestContext(
            productReference = "finance",
            tenantReference = "tenant:integration-test",
            resourceReferences = setOf("question:integration-test", "checklist:integration-test"),
            principalReference = "principal:integration-test",
            requestReference = "request:integration-test",
            correlationReference = null,
            authorityReference = null,
        )
        val businessInput = FinanceGeneralAnalysisBusinessInput(
            question = "Review synthetic notes for omitted checks.",
            checkedChecklistItemIds = listOf("assumptions", "known_facts", "missing_context"),
            lensRef = "general-analysis",
        )
        return FinanceSharedAiRequest(
            context = context,
            skillId = skillId,
            businessInput = businessInput,
            query = FinanceGeneralAnalysisQueryFormatter().format(businessInput),
        )
    }

    private fun postWireRequest(body: String): HttpResponse<String> {
        val endpoint = baseUrl.resolve("/internal/shared-ai/v1/executions")
        val request = HttpRequest.newBuilder(endpoint)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
    }

    private fun wireRequest(skillId: String): String = """
        {
          "context": {
            "scope": {
              "productReference": "finance",
              "tenantReference": "tenant:integration-test",
              "resourceReferences": ["question:integration-test", "checklist:integration-test"]
            },
            "principalReference": "principal:integration-test",
            "requestReference": "request:integration-test",
            "correlationReference": null,
            "authorityReference": null
          },
          "skillId": "$skillId",
          "query": "Review synthetic notes for omitted checks."
        }
    """.trimIndent()
}
