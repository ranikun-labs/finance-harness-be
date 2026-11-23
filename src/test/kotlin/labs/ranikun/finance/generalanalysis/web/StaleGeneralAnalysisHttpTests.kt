package labs.ranikun.finance.generalanalysis.web

import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisPolicyGuard
import labs.ranikun.finance.generalanalysis.adapter.InMemoryGeneralAnalysisStore
import labs.ranikun.finance.generalanalysis.adapter.SafeGeneralAnalysisLensRouter
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisApplicationService
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.identity.adapter.LocalDevCurrentUserAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [StaleGeneralAnalysisHttpTests.Harness::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.address=127.0.0.1",
        "management.server.address=127.0.0.1",
        "management.endpoint.health.validate-group-membership=false",
    ],
)
@ActiveProfiles("test")
class StaleGeneralAnalysisHttpTests {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var json: JsonMapper

    @Autowired
    private lateinit var execution: PausedExecution

    @Test
    fun independentClientsChangeTheSameChecklistWhileAnalysisIsInFlight() {
        val firstTab = HttpClient.newHttpClient()
        val secondTab = HttpClient.newHttpClient()
        val setup = body(
            firstTab.send(
                request("POST", "/questions", """{"question":"How can I verify these general assumptions?"}"""),
                HttpResponse.BodyHandlers.ofString(),
            ),
            201,
        )
        val questionPath = "/questions/${setup.path("questionId").stringValue()}"
        val checklistId = setup.path("checklistId").stringValue()
        val checklistPath = "$questionPath/checklists/$checklistId"
        val startRequest = request("POST", "$questionPath/analysis", """{"checklistId":"$checklistId"}""")

        update(firstTab, checklistPath, "known_facts")
        val pending = firstTab.sendAsync(startRequest, HttpResponse.BodyHandlers.ofString())
        try {
            assertThat(execution.started.await(5, TimeUnit.SECONDS)).isTrue()
            update(secondTab, checklistPath, "assumptions")
        } finally {
            execution.release.countDown()
        }

        val stale = body(pending.get(5, TimeUnit.SECONDS), 200)
        assertThat(stale.path("status").stringValue()).isEqualTo("STALE")
        assertThat(stale.path("output").isNull).isTrue()
        assertThat(lookup(secondTab, stale)).isEqualTo(stale)

        val completed = body(firstTab.send(startRequest, HttpResponse.BodyHandlers.ofString()), 200)
        assertThat(completed.path("status").stringValue()).isEqualTo("COMPLETED")
        assertThat(completed.path("output").path("summary").stringValue()).isEqualTo("Synthetic analysis summary.")
        assertThat(lookup(secondTab, completed)).isEqualTo(completed)

        update(secondTab, checklistPath, "missing_context")
        val prior = lookup(firstTab, completed)
        assertThat(prior.path("status").stringValue()).isEqualTo("STALE")
        assertThat(prior.path("output").isNull).isTrue()
    }

    private fun lookup(client: HttpClient, analysis: JsonNode): JsonNode {
        val id = analysis.path("analysisId").stringValue()
        return body(
            client.send(request("GET", "/analyses/$id"), HttpResponse.BodyHandlers.ofString()),
            200,
        )
    }

    private fun update(client: HttpClient, checklistPath: String, item: String) {
        body(
            client.send(
                request("PUT", checklistPath, """{"checkedItemIds":["$item"],"confirmed":true}"""),
                HttpResponse.BodyHandlers.ofString(),
            ),
            200,
        )
    }

    private fun request(method: String, path: String, jsonBody: String? = null): HttpRequest =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/finance/general-analysis$path"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .method(
                method,
                if (jsonBody == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(jsonBody),
            )
            .build()

    private fun body(response: HttpResponse<String>, expectedStatus: Int): JsonNode {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expectedStatus)
        return json.readTree(response.body())
    }

    class PausedExecution : GeneralAnalysisExecutionPort {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            return GeneralAnalysisExecutionResult.Success(
                GeneralAnalysisCandidate(
                    summary = "Synthetic analysis summary.",
                    observations = listOf("Only the question was supplied."),
                    assumptions = listOf("No external facts were supplied."),
                    nextChecks = listOf("Check primary sources for relevant facts."),
                    evidenceNote = "Source: user-provided question only.",
                ),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ])
    @Import(
        LocalDevCurrentUserAdapter::class,
        InMemoryGeneralAnalysisStore::class,
        FinanceGeneralAnalysisPolicyGuard::class,
        SafeGeneralAnalysisLensRouter::class,
        FinanceGeneralAnalysisOutputValidator::class,
        GeneralAnalysisApplicationService::class,
        GeneralAnalysisController::class,
        PausedExecution::class,
    )
    class Harness
}
