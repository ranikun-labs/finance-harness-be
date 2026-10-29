package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiEvidenceEnvelope
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiEvidenceItem
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiExecutionContext
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiFailureCategory
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiOutcome
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiPort
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequest
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiScope
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiTransportFailure
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiUsage
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

private const val INITIAL_RESPONSE_BUFFER_BYTES = 8_192

/** Finance's local/test HTTP consumer for Shared AI's v1 execution endpoint. */
internal class FinanceSharedAiHttpClient(
    baseUrl: URI,
    private val totalTimeout: Duration = TOTAL_TIMEOUT,
    private val httpClient: HttpClient = newHttpClient(CONNECT_TIMEOUT),
    private val mapper: JsonMapper = strictJsonMapper(),
) : FinanceSharedAiPort {
    private val endpoint = endpointFor(baseUrl)

    override fun execute(request: FinanceSharedAiRequest): FinanceSharedAiOutcome {
        if (!isValidRequest(request)) {
            return FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.INVALID_REQUEST)
        }

        val body = try {
            mapper.writeValueAsString(request.toWireRequest())
        } catch (_: Exception) {
            return FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.INVALID_REQUEST)
        }
        val httpRequest = try {
            HttpRequest.newBuilder(endpoint)
                .timeout(totalTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                .build()
        } catch (_: Exception) {
            return FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.INVALID_REQUEST)
        }
        val bodyHandler = BoundedResponseBodyHandler(MAX_RESPONSE_BYTES)
        val response = try {
            httpClient.send(httpRequest, bodyHandler)
        } catch (_: HttpTimeoutException) {
            return FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.TIMEOUT)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.INTERRUPTED)
        } catch (_: IOException) {
            return if (bodyHandler.exceededLimit) {
                FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.RESPONSE_TOO_LARGE)
            } else {
                FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.NETWORK)
            }
        } catch (_: Exception) {
            return if (bodyHandler.exceededLimit) {
                FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.RESPONSE_TOO_LARGE)
            } else {
                FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.NETWORK)
            }
        }

        if (response.statusCode() != HTTP_OK) {
            return FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.HTTP_STATUS)
        }
        return parseOutcome(response.body())
    }

    private fun parseOutcome(body: String): FinanceSharedAiOutcome = try {
        val root = mapper.readTree(body) ?: return malformedResponse()
        if (!root.isObject || !root.has("outcome") || !root.get("outcome").isTextual) {
            return malformedResponse()
        }
        when (root.get("outcome").stringValue()) {
            SUCCESS_OUTCOME -> parseSuccess(root) ?: malformedResponse()
            TECHNICAL_FAILURE_OUTCOME -> parseTechnicalFailure(root) ?: malformedResponse()
            else -> malformedResponse()
        }
    } catch (_: Exception) {
        malformedResponse()
    }

    private fun parseSuccess(root: JsonNode): FinanceSharedAiOutcome.Success? {
        if (!root.hasExactly(SUCCESS_FIELDS)) return null
        val executionReference = root.requiredText("executionReference")
        val interpretation = root.requiredText("interpretation")
        val warnings = root.requiredTextArray("warnings", MAX_WARNING_COUNT)
        val usage = root.requiredUsage("usage")
        val evidence = root.requiredObject("evidence")
        if (!evidence.hasExactly(EVIDENCE_FIELDS)) return null
        val context = evidence.requiredObject("context").parseExecutionContext() ?: return null
        val itemsNode = evidence.get("items")
        if (!itemsNode.isArray) return null
        if (itemsNode.size() > MAX_EVIDENCE_ITEMS) return null
        val items = (0 until itemsNode.size()).map { index ->
            itemsNode.get(index).parseEvidenceItem() ?: return null
        }
        return FinanceSharedAiOutcome.Success(
            executionReference = executionReference,
            interpretation = interpretation,
            evidence = FinanceSharedAiEvidenceEnvelope(context = context, items = items),
            warnings = warnings,
            usage = usage,
        )
    }

    private fun parseTechnicalFailure(root: JsonNode): FinanceSharedAiOutcome.TechnicalFailure? {
        if (!root.hasExactly(TECHNICAL_FAILURE_FIELDS)) return null
        val executionReference = root.requiredText("executionReference")
        val categoryText = root.requiredText("category")
        val category = FinanceSharedAiFailureCategory.entries.firstOrNull { it.name == categoryText } ?: return null
        return FinanceSharedAiOutcome.TechnicalFailure(executionReference, category)
    }

    private fun JsonNode.parseExecutionContext(): FinanceSharedAiExecutionContext? {
        if (!hasExactly(EXECUTION_CONTEXT_FIELDS)) return null
        val scope = requiredObject("scope").parseScope() ?: return null
        return FinanceSharedAiExecutionContext(
            productReference = scope.productReference,
            tenantReference = scope.tenantReference,
            resourceReferences = scope.resourceReferences,
            principalReference = requiredText("principalReference"),
            requestReference = requiredText("requestReference"),
            executionReference = requiredText("executionReference"),
            correlationReference = requiredNullableText("correlationReference"),
            contextVersion = requiredText("contextVersion"),
            authorityReference = requiredNullableText("authorityReference"),
        )
    }

    private fun JsonNode.parseEvidenceItem(): FinanceSharedAiEvidenceItem? {
        if (!hasExactly(EVIDENCE_ITEM_FIELDS)) return null
        val kind = requiredText("kind")
        if (kind !in EVIDENCE_KINDS) return null
        val observedAtText = requiredText("observedAt")
        if (!observedAtText.endsWith("Z")) return null
        val observedAt = try {
            Instant.parse(observedAtText)
        } catch (_: Exception) {
            return null
        }
        val sourceReferences = requiredTextArray("sourceReferences", MAX_SOURCE_REFERENCES)
        if (sourceReferences.isEmpty() || sourceReferences.distinct().size != sourceReferences.size) return null
        val scope = requiredObject("scopeReference").parseScope() ?: return null
        return FinanceSharedAiEvidenceItem(
            evidenceId = requiredText("evidenceId"),
            kind = kind,
            sourceReferences = sourceReferences.toSet(),
            producerReference = requiredText("producerReference"),
            observedAt = observedAt,
            executionReference = requiredText("executionReference"),
            scopeReference = scope,
            schemaVersion = requiredText("schemaVersion"),
        )
    }

    private fun JsonNode.parseScope(): FinanceSharedAiScope? {
        if (!hasExactly(SCOPE_FIELDS)) return null
        val resourceReferences = requiredTextArray("resourceReferences", MAX_RESOURCE_REFERENCES)
        if (resourceReferences.distinct().size != resourceReferences.size) return null
        return FinanceSharedAiScope(
            productReference = requiredText("productReference"),
            tenantReference = requiredText("tenantReference"),
            resourceReferences = resourceReferences.toSet(),
        )
    }

    private fun isValidRequest(request: FinanceSharedAiRequest): Boolean {
        val context = request.context
        return request.skillId == FINANCE_GENERAL_ANALYSIS_SKILL_ID &&
            request.query.isNotBlank() &&
            context.productReference == FINANCE_PRODUCT_REFERENCE &&
            context.tenantReference.isNotBlank() &&
            context.resourceReferences.isNotEmpty() &&
            context.resourceReferences.none(String::isBlank) &&
            context.principalReference.isNotBlank() &&
            context.requestReference.isNotBlank() &&
            context.correlationReference.isValidOptionalReference() &&
            context.authorityReference.isValidOptionalReference()
    }

    private fun FinanceSharedAiRequest.toWireRequest() = SharedAiV1RequestJson(
        context = SharedAiV1RequestContextJson(
            scope = SharedAiV1ScopeJson(
                productReference = context.productReference,
                tenantReference = context.tenantReference,
                resourceReferences = context.resourceReferences.sorted(),
            ),
            principalReference = context.principalReference,
            requestReference = context.requestReference,
            correlationReference = context.correlationReference,
            authorityReference = context.authorityReference,
        ),
        skillId = skillId,
        query = query,
    )

    private fun JsonNode.hasExactly(fieldNames: Set<String>): Boolean =
        isObject && size() == fieldNames.size && fieldNames.all(::has)

    private fun JsonNode.requiredObject(name: String): JsonNode {
        val value = get(name)
        require(value != null && value.isObject) { "Invalid Shared AI v1 response." }
        return value
    }

    private fun JsonNode.requiredText(name: String): String {
        val value = get(name)
        require(value != null && value.isTextual) { "Invalid Shared AI v1 response." }
        return value.stringValue().also { require(it.isNotBlank()) { "Invalid Shared AI v1 response." } }
    }

    private fun JsonNode.requiredNullableText(name: String): String? {
        require(has(name)) { "Invalid Shared AI v1 response." }
        val value = get(name)
        if (value.isNull) return null
        require(value.isTextual) { "Invalid Shared AI v1 response." }
        return value.stringValue().also { require(it.isNotBlank()) { "Invalid Shared AI v1 response." } }
    }

    private fun JsonNode.requiredTextArray(name: String, maximumItems: Int): List<String> {
        val values = get(name)
        require(values != null && values.isArray) { "Invalid Shared AI v1 response." }
        require(values.size() <= maximumItems) { "Invalid Shared AI v1 response." }
        return (0 until values.size()).map { index ->
            val item = values.get(index)
            require(item.isTextual) { "Invalid Shared AI v1 response." }
            item.stringValue().also { require(it.isNotBlank()) { "Invalid Shared AI v1 response." } }
        }
    }

    private fun JsonNode.requiredUsage(name: String): FinanceSharedAiUsage? {
        require(has(name)) { "Invalid Shared AI v1 response." }
        val value = get(name)
        if (value.isNull) return null
        require(value.hasExactly(USAGE_FIELDS)) { "Invalid Shared AI v1 response." }
        return FinanceSharedAiUsage(
            inputTokens = value.requiredNullableNonnegativeInt("inputTokens"),
            outputTokens = value.requiredNullableNonnegativeInt("outputTokens"),
            totalTokens = value.requiredNullableNonnegativeInt("totalTokens"),
        )
    }

    private fun JsonNode.requiredNullableNonnegativeInt(name: String): Int? {
        require(has(name)) { "Invalid Shared AI v1 response." }
        val value = get(name)
        if (value.isNull) return null
        require(value.isInt && value.intValue() >= 0) { "Invalid Shared AI v1 response." }
        return value.intValue()
    }

    private fun String?.isValidOptionalReference(): Boolean = this == null || isNotBlank()

    private fun malformedResponse() =
        FinanceSharedAiOutcome.TransportFailure(FinanceSharedAiTransportFailure.MALFORMED_RESPONSE)

    private data class SharedAiV1RequestJson(
        val context: SharedAiV1RequestContextJson,
        val skillId: String,
        val query: String,
    )

    private data class SharedAiV1RequestContextJson(
        val scope: SharedAiV1ScopeJson,
        val principalReference: String,
        val requestReference: String,
        val correlationReference: String?,
        val authorityReference: String?,
    )

    private data class SharedAiV1ScopeJson(
        val productReference: String,
        val tenantReference: String,
        val resourceReferences: List<String>,
    )

    private fun endpointFor(baseUrl: URI): URI {
        require(baseUrl.scheme == "http" || baseUrl.scheme == "https") { "Invalid Shared AI base URL." }
        require(!baseUrl.host.isNullOrBlank()) { "Invalid Shared AI base URL." }
        require(baseUrl.userInfo == null) { "Shared AI base URL must not contain userinfo." }
        val host = baseUrl.host.removePrefix("[").removeSuffix("]").lowercase()
        require(host in LOOPBACK_HOSTS) { "Shared AI local/test client must use a loopback base URL." }
        require(baseUrl.rawQuery == null && baseUrl.rawFragment == null) { "Invalid Shared AI base URL." }
        require(baseUrl.path.isNullOrEmpty() || baseUrl.path == "/") { "Invalid Shared AI base URL." }
        return URI.create(baseUrl.toString().trimEnd('/') + EXECUTIONS_PATH)
    }

    private class BoundedResponseBodyHandler(
        private val maximumBytes: Int,
    ) : HttpResponse.BodyHandler<String> {
        @Volatile
        var exceededLimit: Boolean = false
            private set

        override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<String> =
            object : HttpResponse.BodySubscriber<String> {
                private val body = CompletableFuture<String>()
                private val buffer = ByteArrayOutputStream(minOf(maximumBytes, INITIAL_RESPONSE_BUFFER_BYTES))
                private var subscription: Flow.Subscription? = null
                private var receivedBytes = 0

                override fun getBody(): CompletionStage<String> = body

                override fun onSubscribe(subscription: Flow.Subscription) {
                    if (this.subscription != null) {
                        subscription.cancel()
                        return
                    }
                    this.subscription = subscription
                    subscription.request(1)
                }

                override fun onNext(items: List<ByteBuffer>) {
                    if (body.isDone) return
                    try {
                        for (item in items) {
                            val itemBytes = item.remaining()
                            if (itemBytes > maximumBytes - receivedBytes) {
                                exceededLimit = true
                                subscription?.cancel()
                                body.completeExceptionally(ResponseBodyTooLargeException())
                                return
                            }
                            val bytes = ByteArray(itemBytes)
                            item.get(bytes)
                            buffer.write(bytes)
                            receivedBytes += itemBytes
                        }
                        subscription?.request(1)
                    } catch (failure: Exception) {
                        subscription?.cancel()
                        body.completeExceptionally(failure)
                    }
                }

                override fun onError(failure: Throwable) {
                    body.completeExceptionally(failure)
                }

                override fun onComplete() {
                    body.complete(String(buffer.toByteArray(), UTF_8))
                }
            }
    }

    private class ResponseBodyTooLargeException : IOException("Shared AI response exceeded the consumer limit.")

    private companion object {
        const val HTTP_OK = 200
        const val FINANCE_PRODUCT_REFERENCE = "finance"
        const val FINANCE_GENERAL_ANALYSIS_SKILL_ID = "finance.general-analysis@1"
        const val SUCCESS_OUTCOME = "success"
        const val TECHNICAL_FAILURE_OUTCOME = "technicalFailure"
        const val EXECUTIONS_PATH = "/internal/shared-ai/v1/executions"
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_EVIDENCE_ITEMS = 128
        const val MAX_WARNING_COUNT = 64
        const val MAX_SOURCE_REFERENCES = 128
        const val MAX_RESOURCE_REFERENCES = 128
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)
        val TOTAL_TIMEOUT: Duration = Duration.ofSeconds(5)
        val SCOPE_FIELDS = setOf("productReference", "tenantReference", "resourceReferences")
        val EXECUTION_CONTEXT_FIELDS = setOf(
            "scope",
            "principalReference",
            "requestReference",
            "executionReference",
            "correlationReference",
            "contextVersion",
            "authorityReference",
        )
        val EVIDENCE_FIELDS = setOf("context", "items")
        val EVIDENCE_ITEM_FIELDS = setOf(
            "evidenceId",
            "kind",
            "sourceReferences",
            "producerReference",
            "observedAt",
            "executionReference",
            "scopeReference",
            "schemaVersion",
        )
        val SUCCESS_FIELDS = setOf("outcome", "executionReference", "interpretation", "warnings", "usage", "evidence")
        val TECHNICAL_FAILURE_FIELDS = setOf("outcome", "executionReference", "category")
        val USAGE_FIELDS = setOf("inputTokens", "outputTokens", "totalTokens")
        val EVIDENCE_KINDS = setOf("STRUCTURED_PRODUCT_FACT", "RETRIEVED_CONTENT", "MODEL_INTERPRETATION")

        fun newHttpClient(connectTimeout: Duration): HttpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        fun strictJsonMapper(): JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    }
}
