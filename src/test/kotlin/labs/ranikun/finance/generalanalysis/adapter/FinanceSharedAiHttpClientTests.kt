package labs.ranikun.finance.generalanalysis.adapter

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import labs.ranikun.finance.generalanalysis.application.FinanceGeneralAnalysisBusinessInput
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiExecutionContext
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiFailureCategory
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiOutcome
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequest
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequestContext
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiTransportFailure
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisApplicationService
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FinanceSharedAiHttpClientTests {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `posts the exact v1 seed request and maps all Shared evidence fields`() {
        val request = sampleRequest()
        val requestBody = AtomicReference<String>()
        val method = AtomicReference<String>()
        val path = AtomicReference<String>()
        val accept = AtomicReference<String>()
        val contentType = AtomicReference<String>()

        LocalJsonServer { exchange ->
            requestBody.set(exchange.readBody())
            method.set(exchange.requestMethod)
            path.set(exchange.requestURI.path)
            accept.set(exchange.requestHeaders.getFirst("Accept"))
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            exchange.respond(200, successResponse(request.context))
        }.use { server ->
            val result = FinanceSharedAiHttpClient(server.baseUrl).execute(request)

            assertThat(method.get()).isEqualTo("POST")
            assertThat(path.get()).isEqualTo(EXECUTIONS_PATH)
            assertThat(accept.get()).isEqualTo("application/json")
            assertThat(contentType.get()).startsWith("application/json")
            val json = mapper.readTree(requestBody.get())
            assertHasExactly(json, "context", "skillId", "query")
            assertThat(json.get("skillId").stringValue()).isEqualTo("finance.general-analysis@1")
            assertThat(json.get("query").stringValue()).isEqualTo(request.query)
            val context = json.get("context")
            assertHasExactly(
                context,
                "scope",
                "principalReference",
                "requestReference",
                "correlationReference",
                "authorityReference",
            )
            assertThat(context.has("executionReference")).isFalse()
            assertThat(context.has("contextVersion")).isFalse()
            assertThat(context.get("correlationReference").isNull).isTrue()
            assertThat(context.get("authorityReference").isNull).isTrue()
            assertThat(context.get("scope").get("resourceReferences").textValues())
                .containsExactly("checklist:fixture-1", "question:fixture-1")

            assertThat(result).isInstanceOf(FinanceSharedAiOutcome.Success::class.java)
            val success = result as FinanceSharedAiOutcome.Success
            assertThat(success.executionReference).isEqualTo("execution:shared-issued-1")
            assertThat(success.evidence.context).isEqualTo(
                FinanceSharedAiExecutionContext(
                    productReference = request.context.productReference,
                    tenantReference = request.context.tenantReference,
                    resourceReferences = request.context.resourceReferences,
                    principalReference = request.context.principalReference,
                    requestReference = request.context.requestReference,
                    executionReference = "execution:shared-issued-1",
                    correlationReference = null,
                    contextVersion = "1",
                    authorityReference = null,
                ),
            )
            assertThat(success.warnings).containsExactly("synthetic warning")
            assertThat(success.usage?.inputTokens).isEqualTo(11)
            assertThat(success.usage?.outputTokens).isEqualTo(22)
            assertThat(success.usage?.totalTokens).isEqualTo(33)
            assertThat(success.evidence.items).hasSize(2)
            val fact = success.evidence.items.first()
            assertThat(fact.evidenceId).isEqualTo("evidence:fact-1")
            assertThat(fact.kind).isEqualTo("STRUCTURED_PRODUCT_FACT")
            assertThat(fact.sourceReferences).containsExactly("finance:question")
            assertThat(fact.producerReference).isEqualTo("finance.synthetic-tool")
            assertThat(fact.observedAt.toString()).isEqualTo("2026-10-29T01:00:00Z")
            assertThat(fact.executionReference).isEqualTo(success.executionReference)
            assertThat(fact.scopeReference.productReference).isEqualTo("finance")
            assertThat(fact.scopeReference.tenantReference).isEqualTo("tenant:fixture-1")
            assertThat(fact.scopeReference.resourceReferences)
                .containsExactlyInAnyOrder("question:fixture-1", "checklist:fixture-1")
            assertThat(fact.schemaVersion).isEqualTo("1")
            val interpretationEvidence = success.evidence.items.last()
            assertThat(interpretationEvidence.kind).isEqualTo("MODEL_INTERPRETATION")
            assertThat(interpretationEvidence.sourceReferences).containsExactly("evidence:fact-1")
        }
    }

    @Test
    fun `maps Shared technical failure and hides transport and malformed response details`() {
        val request = sampleRequest()
        LocalJsonServer { exchange ->
            exchange.readBody()
            exchange.respond(200, technicalFailureResponse())
        }.use { server ->
            val result = FinanceSharedAiHttpClient(server.baseUrl).execute(request)
            assertThat(result).isEqualTo(
                FinanceSharedAiOutcome.TechnicalFailure(
                    "execution:shared-issued-1",
                    FinanceSharedAiFailureCategory.MODEL_FAILURE,
                ),
            )
        }

        listOf(
            400 to """{"detail":"private request detail"}""",
            404 to """{"detail":"route detail"}""",
            500 to """{"detail":"internal stack trace"}""",
            200 to """{"outcome":"success","interpretation":"secret candidate"}""",
        ).forEach { (status, body) ->
            LocalJsonServer { exchange ->
                exchange.readBody()
                exchange.respond(status, body)
            }.use { server ->
                val result = FinanceSharedAiHttpClient(server.baseUrl).execute(request)
                assertThat(result).isInstanceOf(FinanceSharedAiOutcome.TransportFailure::class.java)
                assertThat(result.toString()).doesNotContain("private request detail", "internal stack trace", "secret candidate")
                val failure = result as FinanceSharedAiOutcome.TransportFailure
                val expected = if (status == 200) {
                    FinanceSharedAiTransportFailure.MALFORMED_RESPONSE
                } else {
                    FinanceSharedAiTransportFailure.HTTP_STATUS
                }
                assertThat(failure.category).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `rejects oversized response bodies and excessive evidence or warning counts`() {
        val request = sampleRequest()
        val oversized = executeBody("x".repeat(MAX_RESPONSE_BYTES + 1), request)
        assertThat(oversized).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.RESPONSE_TOO_LARGE),
        )

        val excessiveWarnings = mapper.readTree(successResponse(request.context)) as ObjectNode
        val warnings = excessiveWarnings.putArray("warnings")
        repeat(MAX_WARNING_COUNT + 1) { warnings.add("warning $it") }
        assertThat(executeBody(mapper.writeValueAsString(excessiveWarnings), request)).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
        )

        val excessiveEvidence = mapper.readTree(successResponse(request.context)) as ObjectNode
        val evidence = excessiveEvidence.get("evidence") as ObjectNode
        val items = evidence.putArray("items")
        repeat(MAX_EVIDENCE_ITEMS + 1) { index ->
            items.add(
                evidenceItemJson(
                    request.context,
                    "execution:shared-issued-1",
                    "evidence:$index",
                    "STRUCTURED_PRODUCT_FACT",
                ),
            )
        }
        assertThat(executeBody(mapper.writeValueAsString(excessiveEvidence), request)).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
        )
    }

    @Test
    fun `rejects oversized chunked body when content length is unknown`() {
        val result = executeChunkedBody("x".repeat(MAX_RESPONSE_BYTES + 1))

        assertThat(result).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.RESPONSE_TOO_LARGE),
        )
    }

    @Test
    fun `rejects 129 source references and 129 resource references`() {
        val request = sampleRequest()

        val excessiveSourceRefs = mapper.readTree(successResponse(request.context)) as ObjectNode
        val items = excessiveSourceRefs.get("evidence").get("items")
        val firstItem = items.get(0) as ObjectNode
        val sourceReferences = firstItem.putArray("sourceReferences")
        repeat(MAX_REFERENCES + 1) { sourceReferences.add("source:$it") }
        assertThat(executeBody(mapper.writeValueAsString(excessiveSourceRefs), request)).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
        )

        val excessiveResourceRefs = mapper.readTree(successResponse(request.context)) as ObjectNode
        val returnedContext = excessiveResourceRefs.get("evidence").get("context") as ObjectNode
        val scope = returnedContext.get("scope") as ObjectNode
        val resourceReferences = scope.putArray("resourceReferences")
        repeat(MAX_REFERENCES + 1) { resourceReferences.add("resource:$it") }
        assertThat(executeBody(mapper.writeValueAsString(excessiveResourceRefs), request)).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
        )
    }

    @Test
    fun `rejects every negative usage count without exposing the candidate`() {
        val request = sampleRequest()

        listOf("inputTokens", "outputTokens", "totalTokens").forEach { countField ->
            val response = mapper.readTree(successResponse(request.context)) as ObjectNode
            (response.get("usage") as ObjectNode).put(countField, -1)

            val result = executeBody(mapper.writeValueAsString(response), request)

            assertThat(result).isEqualTo(
                FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
            )
            assertThat(result.toString()).doesNotContain("The synthetic review is ready.")
        }
    }

    @Test
    fun `rejects missing and overflowing usage counts but accepts the integer boundary`() {
        val request = sampleRequest()

        val missingCount = mapper.readTree(successResponse(request.context)) as ObjectNode
        (missingCount.get("usage") as ObjectNode).remove("outputTokens")
        assertThat(executeBody(mapper.writeValueAsString(missingCount), request)).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
        )

        val overflowingCount = mapper.readTree(successResponse(request.context)) as ObjectNode
        (overflowingCount.get("usage") as ObjectNode).put("totalTokens", Int.MAX_VALUE.toLong() + 1L)
        assertThat(executeBody(mapper.writeValueAsString(overflowingCount), request)).isEqualTo(
            FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE),
        )

        val boundaryCount = mapper.readTree(successResponse(request.context)) as ObjectNode
        (boundaryCount.get("usage") as ObjectNode).put("totalTokens", Int.MAX_VALUE)
        val boundaryResult = executeBody(mapper.writeValueAsString(boundaryCount), request)
        assertThat(boundaryResult).isInstanceOf(FinanceSharedAiOutcome.Success::class.java)
        assertThat((boundaryResult as FinanceSharedAiOutcome.Success).usage?.totalTokens)
            .isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `timeout fails once without retry and repeated submit calls create new results`() {
        val request = sampleRequest()
        val timeoutRequests = AtomicInteger()
        val timeoutReached = CountDownLatch(1)
        LocalJsonServer { exchange ->
            exchange.readBody()
            timeoutRequests.incrementAndGet()
            timeoutReached.countDown()
            try {
                Thread.sleep(1_000)
                exchange.respond(200, successResponse(request.context))
            } catch (_: Exception) {
                exchange.close()
            }
        }.use { server ->
            val client = FinanceSharedAiHttpClient(server.baseUrl, totalTimeout = Duration.ofMillis(300))
            val result = client.execute(request)
            assertThat(result).isEqualTo(
                FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.TIMEOUT),
            )
            assertThat(timeoutReached.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(timeoutRequests.get()).isEqualTo(1)
        }

        val executions = AtomicInteger()
        LocalJsonServer { exchange ->
            exchange.readBody()
            val execution = executions.incrementAndGet()
            exchange.respond(200, successResponse(request.context, "execution:shared-$execution"))
        }.use { server ->
            val client = FinanceSharedAiHttpClient(server.baseUrl)
            val first = client.execute(request) as FinanceSharedAiOutcome.Success
            val second = client.execute(request) as FinanceSharedAiOutcome.Success
            assertThat(executions.get()).isEqualTo(2)
            assertThat(first.executionReference).isNotEqualTo(second.executionReference)
        }
    }

    @Test
    fun `Finance policy blocks before HTTP and output validation blocks prohibited result`() {
        val requestCount = AtomicInteger()
        LocalJsonServer { exchange ->
            val wireRequest = mapper.readTree(exchange.readBody())
            val context = requestContextFromWire(wireRequest)
            val invocation = requestCount.incrementAndGet()
            val interpretation = if (invocation == 1) {
                candidateJson()
            } else {
                candidateJson(summary = "Price target is 120; buy now.")
            }
            exchange.respond(200, successResponse(context, "execution:shared-$invocation", interpretation))
        }.use { server ->
            val service = service(
                FinanceGeneralAnalysisExecutionAdapter(
                    LocalFinanceExecutionContextFactory("tenant:explicit-local-test"),
                    FinanceSharedAiHttpClient(server.baseUrl),
                ),
            )
            val safeQuestion = service.createQuestion("How can I verify these facts?")
            service.confirmChecklist(safeQuestion.questionId, safeQuestion.checklistId, setOf("known_facts"), true)
            val completed = service.analyze(safeQuestion.questionId, safeQuestion.checklistId)
            assertThat(completed.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
            assertThat(completed.output?.summary).isEqualTo("The synthetic review is ready.")

            val riskyQuestion = service.createQuestion("Should I purchase shares?")
            service.confirmChecklist(riskyQuestion.questionId, riskyQuestion.checklistId, emptySet(), true)
            val blocked = service.analyze(riskyQuestion.questionId, riskyQuestion.checklistId)
            assertThat(blocked.status).isEqualTo(GeneralAnalysisStatus.BLOCKED)
            assertThat(blocked.output).isNull()

            val invalidQuestion = service.createQuestion("How can I check the second scenario?")
            service.confirmChecklist(invalidQuestion.questionId, invalidQuestion.checklistId, emptySet(), true)
            val rejected = service.analyze(invalidQuestion.questionId, invalidQuestion.checklistId)
            assertThat(rejected.status).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(rejected.output).isNull()
            assertThat(service.findAnalysis(rejected.analysisId).output).isNull()
            assertThat(requestCount.get()).isEqualTo(2)
        }
    }

    @Test
    fun `rejects non loopback and credentialed base URLs`() {
        assertThatThrownBy { FinanceSharedAiHttpClient(URI.create("http://shared-ai.example")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FinanceSharedAiHttpClient(URI.create("http://user:secret@127.0.0.1:8080")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun service(executionPort: GeneralAnalysisExecutionPort) = GeneralAnalysisApplicationService(
        currentUserPort = object : CurrentUserPort {
            override fun currentUserId() = IdentityUserId("test-owner")
        },
        store = InMemoryGeneralAnalysisStore(),
        policyGuard = FinanceGeneralAnalysisPolicyGuard(),
        lensRouter = SafeGeneralAnalysisLensRouter(),
        executionPort = executionPort,
        outputValidator = FinanceGeneralAnalysisOutputValidator(),
    )

    private fun sampleRequest() = FinanceSharedAiRequest(
        context = FinanceSharedAiRequestContext(
            productReference = "finance",
            tenantReference = "tenant:fixture-1",
            resourceReferences = setOf("question:fixture-1", "checklist:fixture-1"),
            principalReference = "principal:fixture-1",
            requestReference = "request:fixture-1",
            correlationReference = null,
            authorityReference = null,
        ),
        skillId = "finance.general-analysis@1",
        businessInput = FinanceGeneralAnalysisBusinessInput(
            question = "Review synthetic investment notes for omitted checks.",
            checkedChecklistItemIds = listOf("assumptions", "known_facts"),
            lensRef = "general-analysis",
        ),
        query = "Finance General Analysis\nQuestion:\nReview synthetic investment notes for omitted checks.\n",
    )

    private fun requestContextFromWire(request: JsonNode): FinanceSharedAiRequestContext {
        val context = request.get("context")
        val scope = context.get("scope")
        return FinanceSharedAiRequestContext(
            productReference = scope.get("productReference").stringValue(),
            tenantReference = scope.get("tenantReference").stringValue(),
            resourceReferences = scope.get("resourceReferences").textValues().toSet(),
            principalReference = context.get("principalReference").stringValue(),
            requestReference = context.get("requestReference").stringValue(),
            correlationReference = context.get("correlationReference").takeUnless(JsonNode::isNull)?.stringValue(),
            authorityReference = context.get("authorityReference").takeUnless(JsonNode::isNull)?.stringValue(),
        )
    }

    private fun executeBody(
        body: String,
        request: FinanceSharedAiRequest = sampleRequest(),
        status: Int = 200,
    ): FinanceSharedAiOutcome = LocalJsonServer { exchange ->
        exchange.readBody()
        exchange.respond(status, body)
    }.use { server ->
        FinanceSharedAiHttpClient(server.baseUrl).execute(request)
    }

    private fun executeChunkedBody(
        body: String,
        request: FinanceSharedAiRequest = sampleRequest(),
        status: Int = 200,
    ): FinanceSharedAiOutcome = LocalJsonServer { exchange ->
        exchange.readBody()
        exchange.respondChunked(status, body)
    }.use { server ->
        FinanceSharedAiHttpClient(server.baseUrl).execute(request)
    }

    private fun successResponse(
        requestContext: FinanceSharedAiRequestContext,
        executionReference: String = "execution:shared-issued-1",
        interpretation: String = candidateJson(),
    ): String {
        val response = mapper.createObjectNode()
        response.put("outcome", "success")
        response.put("executionReference", executionReference)
        response.put("interpretation", interpretation)
        response.putArray("warnings").add("synthetic warning")
        response.putObject("usage").apply {
            put("inputTokens", 11)
            put("outputTokens", 22)
            put("totalTokens", 33)
        }
        val evidence = response.putObject("evidence")
        evidence.set("context", executionContextJson(requestContext, executionReference))
        val items = evidence.putArray("items")
        items.add(evidenceItemJson(requestContext, executionReference, "evidence:fact-1", "STRUCTURED_PRODUCT_FACT"))
        items.add(
            evidenceItemJson(
                requestContext,
                executionReference,
                "evidence:interpretation-1",
                "MODEL_INTERPRETATION",
                listOf("evidence:fact-1"),
                "model.synthetic-gateway",
            ),
        )
        return mapper.writeValueAsString(response)
    }

    private fun executionContextJson(context: FinanceSharedAiRequestContext, executionReference: String) =
        mapper.createObjectNode().apply {
            set("scope", scopeJson(context.productReference, context.tenantReference, context.resourceReferences))
            put("principalReference", context.principalReference)
            put("requestReference", context.requestReference)
            put("executionReference", executionReference)
            putNullable("correlationReference", context.correlationReference)
            put("contextVersion", "1")
            putNullable("authorityReference", context.authorityReference)
        }

    private fun evidenceItemJson(
        context: FinanceSharedAiRequestContext,
        executionReference: String,
        evidenceId: String,
        kind: String,
        sourceReferences: List<String> = listOf("finance:question"),
        producerReference: String = "finance.synthetic-tool",
    ) = mapper.createObjectNode().apply {
        put("evidenceId", evidenceId)
        put("kind", kind)
        putArray("sourceReferences").also { array -> sourceReferences.forEach(array::add) }
        put("producerReference", producerReference)
        put("observedAt", "2026-10-29T01:00:00Z")
        put("executionReference", executionReference)
        set(
            "scopeReference",
            scopeJson(context.productReference, context.tenantReference, context.resourceReferences),
        )
        put("schemaVersion", "1")
    }

    private fun scopeJson(productReference: String, tenantReference: String, resources: Set<String>) =
        mapper.createObjectNode().apply {
            put("productReference", productReference)
            put("tenantReference", tenantReference)
            putArray("resourceReferences").also { array -> resources.sorted().forEach(array::add) }
        }

    private fun candidateJson(summary: String = "The synthetic review is ready.") =
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("summary", summary)
                putArray("observations").add("Only synthetic fixture data was supplied.")
                putArray("assumptions").add("No external facts were supplied.")
                putArray("nextChecks").add("Verify relevant facts with a primary source.")
                put("evidenceNote", "Evidence: synthetic fixture only.")
            },
        )

    private fun technicalFailureResponse(): String =
        """{"outcome":"technicalFailure","executionReference":"execution:shared-issued-1","category":"MODEL_FAILURE"}"""

    private fun assertHasExactly(node: JsonNode, vararg expected: String) {
        assertThat(node.isObject).isTrue()
        assertThat(node.size()).isEqualTo(expected.size)
        expected.forEach { assertThat(node.has(it)).isTrue() }
    }

    private fun JsonNode.textValues(): List<String> =
        (0 until size()).map { get(it).stringValue() }

    private fun HttpExchange.readBody(): String = requestBody.use { String(it.readAllBytes(), UTF_8) }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun HttpExchange.respondChunked(status: Int, body: String) {
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, 0)
        try {
            responseBody.use { it.write(body.toByteArray(UTF_8)) }
        } catch (_: Exception) {
            // The bounded client may cancel the chunked body as soon as it crosses its byte limit.
        }
        close()
    }

    private fun ObjectNode.putNullable(name: String, value: String?) {
        if (value == null) putNull(name) else put(name, value)
    }

    private class LocalJsonServer(
        handler: (HttpExchange) -> Unit,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val baseUrl: URI

        init {
            server.executor = executor
            server.createContext(EXECUTIONS_PATH, HttpHandler(handler))
            server.start()
            baseUrl = URI.create("http://127.0.0.1:${server.address.port}")
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        const val EXECUTIONS_PATH = "/internal/shared-ai/v1/executions"
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_EVIDENCE_ITEMS = 128
        const val MAX_WARNING_COUNT = 64
        const val MAX_REFERENCES = 128
    }
}
