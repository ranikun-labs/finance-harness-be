package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.FinanceExecutionContextFactory
import labs.ranikun.finance.generalanalysis.application.FinanceGeneralAnalysisBusinessInput
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiExecutionContext
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiEvidenceEnvelope
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiEvidenceItem
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiFailureCategory
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiOutcome
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiPort
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequest
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequestContext
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiScope
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisApplicationService
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLens
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLensRouter
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisPolicyGuard
import labs.ranikun.finance.generalanalysis.application.PolicyClassification
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

class FinanceGeneralAnalysisExecutionAdapterTests {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `maps verified Finance input to typed skill request and accepts correlated Shared success`() {
        val fixture = fixture()
        val input = executionInput(fixture)
        val order = mutableListOf<String>()
        val contextFactory = FixtureContextFactory(fixture.tenantReference, order)
        val port = RecordingFinanceSharedAiPort(order) { request -> success(request.context) }
        val adapter = adapter(contextFactory, port)

        val result = adapter.execute(input)

        assertThat(result).isInstanceOf(GeneralAnalysisExecutionResult.Success::class.java)
        assertThat((result as GeneralAnalysisExecutionResult.Success).candidate).isEqualTo(candidate())
        val request = port.requests.single()
        assertThat(request.skillId).isEqualTo("finance.general-analysis@1")
        assertThat(request.businessInput).isEqualTo(
            FinanceGeneralAnalysisBusinessInput(
                question = fixture.question,
                checkedChecklistItemIds = listOf("assumptions", "known_facts"),
                lensRef = "general-analysis",
            ),
        )
        assertThat(request.query).isEqualTo(
            "Finance General Analysis\n" +
                "Lens: general-analysis\n" +
                "Question:\n" +
                "${fixture.question}\n" +
                "Checked checklist item IDs:\n" +
                "- assumptions\n" +
                "- known_facts\n" +
                "Output format:\n" +
                "Return exactly one JSON object with these fields:\n" +
                "summary: string\n" +
                "observations: non-empty array of strings\n" +
                "assumptions: non-empty array of strings\n" +
                "nextChecks: non-empty array of strings\n" +
                "evidenceNote: string\n" +
                "Do not include markdown, trade advice, targets, forecasts, or unsupported facts.\n",
        )
        assertThat(request.context.productReference).isEqualTo("finance")
        assertThat(request.context.tenantReference).isEqualTo(fixture.tenantReference)
        assertThat(request.context.principalReference).isEqualTo(input.owner.value)
        assertThat(request.context.resourceReferences).containsExactlyInAnyOrder(
            "question:${input.questionId}",
            "checklist:${input.checklistId}",
        )
        assertThat(request.context.requestReference).isEqualTo(input.analysisRequestId.toString())
        assertThat(request.context.correlationReference).isNotBlank()
        assertThat(request.context.authorityReference).isEqualTo("authority:fixture-1")
        assertThat(contextFactory.calls.single()).containsExactly(
            input.owner,
            input.questionId,
            input.checklistId,
            input.analysisRequestId,
        )
    }

    @Test
    fun `application validates shared success before exposing candidate and policy blocks port`() {
        val fixture = fixture()
        val harness = Harness(fixture)
        val question = harness.service.createQuestion("How can I verify the claims?")
        harness.service.confirmChecklist(question.questionId, question.checklistId, setOf("known_facts"), true)

        val analysis = harness.service.analyze(question.questionId, question.checklistId)

        assertThat(analysis.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(analysis.output).isEqualTo(candidate())
        assertThat(harness.order).containsExactly("policy", "lens", "context", "shared-ai", "output-validator")

        val riskyHarness = Harness(fixture, policyDelegate = FinanceGeneralAnalysisPolicyGuard())
        val riskyQuestion = riskyHarness.service.createQuestion("Should I purchase shares?")
        riskyHarness.service.confirmChecklist(riskyQuestion.questionId, riskyQuestion.checklistId, emptySet(), true)

        val blocked = riskyHarness.service.analyze(riskyQuestion.questionId, riskyQuestion.checklistId)

        assertThat(blocked.status).isEqualTo(GeneralAnalysisStatus.BLOCKED)
        assertThat(blocked.output).isNull()
        assertThat(riskyHarness.port.requests).isEmpty()
        assertThat(riskyHarness.order).containsExactly("policy")
    }

    @Test
    fun `optional correlation and authority references remain absent in request and returned context`() {
        val fixture = fixture()
        val harness = Harness(
            fixture,
            contextOverride = { context -> context.copy(correlationReference = null, authorityReference = null) },
        )
        val question = harness.service.createQuestion("How can I verify this claim?")
        harness.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

        val analysis = harness.service.analyze(question.questionId, question.checklistId)

        assertThat(analysis.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(harness.port.requests.single().context.correlationReference).isNull()
        assertThat(harness.port.requests.single().context.authorityReference).isNull()
    }

    @Test
    fun `technical model failure and invalid context remain failed without output`() {
        val fixture = fixture()
        val modelFailureHarness = Harness(fixture, outcome = { request ->
            FinanceSharedAiOutcome.TechnicalFailure(
                "execution:issued-by-shared-ai",
                FinanceSharedAiFailureCategory.MODEL_FAILURE,
            )
        })
        val modelFailure = modelFailureHarness.analyze(fixture.question, fixture.checkedItemIds)
        assertThat(modelFailure.status).isEqualTo(GeneralAnalysisStatus.FAILED)
        assertThat(modelFailure.output).isNull()
        assertThat(modelFailureHarness.port.requests).hasSize(1)
        assertThat(modelFailureHarness.order).doesNotContain("output-validator")

        val invalidContexts = listOf<(FinanceSharedAiRequestContext) -> FinanceSharedAiRequestContext>(
            { context -> context.copy(productReference = "other") },
            { context -> context.copy(tenantReference = " ") },
            { context -> context.copy(principalReference = "principal:foreign") },
            { context -> context.copy(resourceReferences = setOf("question:foreign")) },
            { context -> context.copy(requestReference = "request:foreign") },
            { context -> context.copy(correlationReference = " ") },
            { context -> context.copy(authorityReference = " ") },
        )
        invalidContexts.forEach { contextOverride ->
            val invalidContextHarness = Harness(fixture, contextOverride = contextOverride)
            val invalidContext = invalidContextHarness.analyze(fixture.question, fixture.checkedItemIds)
            assertThat(invalidContext.status).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(invalidContext.output).isNull()
            assertThat(invalidContextHarness.port.requests).isEmpty()
            assertThat(invalidContextHarness.order).doesNotContain("shared-ai")
        }
    }

    @Test
    fun `evidence and correlation mismatches fail before parsing or exposing interpretation`() {
        val fixture = fixture()
        val mutations: List<(FinanceSharedAiRequest) -> FinanceSharedAiOutcome> = listOf(
            { request -> success(request.context).copy(executionReference = "execution:other") },
            { request -> success(request.context).copy(executionReference = " ") },
            { request ->
                success(request.context).copy(
                    evidence = success(request.context).evidence.copy(
                        context = success(request.context).evidence.context.copy(correlationReference = "correlation:other"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(requestReference = "request:foreign"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(resourceReferences = setOf("question:foreign")),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(productReference = "other"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(tenantReference = "tenant:foreign"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(authorityReference = "authority:foreign"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(contextVersion = "2"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        context = valid.evidence.context.copy(principalReference = "principal:foreign"),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        items = listOf(valid.evidence.items.single().copy(executionReference = "execution:other")),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        items = listOf(
                            valid.evidence.items.single().copy(
                                scopeReference = valid.evidence.items.single().scopeReference.copy(
                                    resourceReferences = setOf("journal:foreign"),
                                ),
                            ),
                        ),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        items = listOf(valid.evidence.items.single().copy(kind = "UNSUPPORTED_KIND")),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        items = listOf(valid.evidence.items.single().copy(producerReference = " ")),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        items = listOf(valid.evidence.items.single().copy(schemaVersion = " ")),
                    ),
                )
            },
            { request ->
                val valid = success(request.context)
                valid.copy(evidence = valid.evidence.copy(items = emptyList()))
            },
            { request ->
                val valid = success(request.context)
                valid.copy(
                    evidence = valid.evidence.copy(
                        items = listOf(valid.evidence.items.single(), valid.evidence.items.single()),
                    ),
                )
            },
        )

        mutations.forEach { mutation ->
            val harness = Harness(fixture, mutation)
            val analysis = harness.analyze(fixture.question, fixture.checkedItemIds)

            assertThat(analysis.status).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(analysis.output).isNull()
            assertThat(harness.service.findAnalysis(analysis.analysisId).output).isNull()
            assertThat(harness.order).doesNotContain("output-validator")
        }
    }

    @Test
    fun `blank malformed and prohibited interpretations fail closed`() {
        val fixture = fixture()
        val invalidInterpretations = listOf(
            " ",
            "not-json",
            "${candidateJson()} trailing-text",
            candidateJson(summary = ""),
            candidateJson(summary = "Buy now."),
            """{"summary":"A","observations":["ok"],"assumptions":["ok"],"nextChecks":["ok"],"evidenceNote":"ok","extra":"no"}""",
            """{"summary":"A","summary":"B","observations":["ok"],"assumptions":["ok"],"nextChecks":["ok"],"evidenceNote":"ok"}""",
        )
        invalidInterpretations.forEach { interpretation ->
            val harness = Harness(fixture, outcome = { request -> success(request.context, interpretation) })
            val analysis = harness.analyze(fixture.question, fixture.checkedItemIds)

            assertThat(analysis.status).describedAs(interpretation).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(analysis.output).describedAs(interpretation).isNull()
            assertThat(harness.service.findAnalysis(analysis.analysisId).output).isNull()
        }
    }

    @Test
    fun `repeated analysis calls invoke the shared ai port again`() {
        val fixture = fixture()
        val harness = Harness(fixture)
        val question = harness.service.createQuestion("How can I verify this claim?")
        harness.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

        val first = harness.service.analyze(question.questionId, question.checklistId)
        val second = harness.service.analyze(question.questionId, question.checklistId)

        assertThat(first.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(second.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(second.analysisId).isNotEqualTo(first.analysisId)
        assertThat(harness.port.requests).hasSize(2)
        assertThat(harness.port.requests[0].businessInput).isEqualTo(harness.port.requests[1].businessInput)
        assertThat(harness.port.requests[0].context.requestReference)
            .isNotEqualTo(harness.port.requests[1].context.requestReference)
    }

    private fun adapter(
        contextFactory: FinanceExecutionContextFactory,
        port: FinanceSharedAiPort,
    ) = FinanceGeneralAnalysisExecutionAdapter(contextFactory, port)

    private fun executionInput(fixture: Fixture) = GeneralAnalysisExecutionInput(
        owner = IdentityUserId(fixture.principalReference),
        questionId = UUID.randomUUID(),
        checklistId = UUID.randomUUID(),
        analysisRequestId = UUID.randomUUID(),
        question = fixture.question,
        checkedItemIds = fixture.checkedItemIds,
        lens = GeneralAnalysisLens.GENERAL_ANALYSIS,
    )

    private fun success(
        context: FinanceSharedAiRequestContext,
        interpretation: String = candidateJson(),
        executionReference: String = "execution:issued-by-shared-ai",
    ): FinanceSharedAiOutcome.Success {
        val resultContext = FinanceSharedAiExecutionContext(
            productReference = context.productReference,
            tenantReference = context.tenantReference,
            resourceReferences = context.resourceReferences,
            principalReference = context.principalReference,
            requestReference = context.requestReference,
            executionReference = executionReference,
            correlationReference = context.correlationReference,
            contextVersion = "1",
            authorityReference = context.authorityReference,
        )
        return FinanceSharedAiOutcome.Success(
            executionReference = executionReference,
            interpretation = interpretation,
            evidence = FinanceSharedAiEvidenceEnvelope(
                context = resultContext,
                items = listOf(
                    FinanceSharedAiEvidenceItem(
                        evidenceId = "evidence:fixture-1",
                        kind = "STRUCTURED_PRODUCT_FACT",
                        sourceReferences = setOf("source:fixture-1"),
                        producerReference = "finance.fixture",
                        observedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        executionReference = executionReference,
                        scopeReference = FinanceSharedAiScope(
                            productReference = resultContext.productReference,
                            tenantReference = resultContext.tenantReference,
                            resourceReferences = resultContext.resourceReferences,
                        ),
                        schemaVersion = "1",
                    ),
                ),
            ),
        )
    }

    private fun candidate() = GeneralAnalysisCandidate(
        summary = "The synthetic question is ready for a general review.",
        observations = listOf("Only the synthetic question was supplied."),
        assumptions = listOf("No external facts were supplied."),
        nextChecks = listOf("Verify relevant facts with a primary source."),
        evidenceNote = "Evidence: synthetic fixture only.",
    )

    private fun candidateJson(
        summary: String = "The synthetic question is ready for a general review.",
    ) = mapper.writeValueAsString(
        mapper.createObjectNode().apply {
            put("summary", summary)
            putArray("observations").add("Only the synthetic question was supplied.")
            putArray("assumptions").add("No external facts were supplied.")
            putArray("nextChecks").add("Verify relevant facts with a primary source.")
            put("evidenceNote", "Evidence: synthetic fixture only.")
        },
    )

    private fun fixture(): Fixture {
        val root = requireNotNull(javaClass.getResourceAsStream("/finance-general-analysis-consumer-fixture.json"))
            .use { mapper.readTree(String(it.readBytes(), UTF_8)) }
        val context = root.get("context")
        val businessInput = root.get("businessInput")
        val checked = businessInput.get("checkedChecklistItemIds")
        return Fixture(
            question = businessInput.get("question").stringValue(),
            checkedItemIds = setOf(checked.get(0).stringValue(), checked.get(1).stringValue()),
            principalReference = context.get("principalReference").stringValue(),
            tenantReference = context.get("scope").get("tenantReference").stringValue(),
        )
    }

    private data class Fixture(
        val question: String,
        val checkedItemIds: Set<String>,
        val principalReference: String,
        val tenantReference: String,
    )

    private class FixtureContextFactory(
        private val tenantReference: String,
        private val order: MutableList<String>? = null,
        private val contextOverride: (FinanceSharedAiRequestContext) -> FinanceSharedAiRequestContext = { it },
    ) : FinanceExecutionContextFactory {
        val calls = mutableListOf<List<Any>>()

        override fun create(
            owner: IdentityUserId,
            questionId: UUID,
            checklistId: UUID,
            analysisRequestId: UUID,
        ): FinanceSharedAiRequestContext {
            order?.add("context")
            calls += listOf(owner, questionId, checklistId, analysisRequestId)
            return contextOverride(
                FinanceSharedAiRequestContext(
                    productReference = "finance",
                    tenantReference = tenantReference,
                    resourceReferences = setOf("question:$questionId", "checklist:$checklistId"),
                    principalReference = owner.value,
                    requestReference = analysisRequestId.toString(),
                    correlationReference = "correlation:$analysisRequestId",
                    authorityReference = "authority:fixture-1",
                ),
            )
        }
    }

    private class RecordingFinanceSharedAiPort(
        private val order: MutableList<String>,
        private val outcome: (FinanceSharedAiRequest) -> FinanceSharedAiOutcome,
    ) : FinanceSharedAiPort {
        val requests = mutableListOf<FinanceSharedAiRequest>()

        override fun execute(request: FinanceSharedAiRequest): FinanceSharedAiOutcome {
            order += "shared-ai"
            requests += request
            return outcome(request)
        }
    }

    private inner class Harness(
        fixture: Fixture,
        outcome: (FinanceSharedAiRequest) -> FinanceSharedAiOutcome = { request -> success(request.context) },
        private val contextOverride: (FinanceSharedAiRequestContext) -> FinanceSharedAiRequestContext = { it },
        policyDelegate: GeneralAnalysisPolicyGuard = safePolicy(),
    ) {
        val order = mutableListOf<String>()
        val port = RecordingFinanceSharedAiPort(order, outcome)
        private val contextFactory = FixtureContextFactory(fixture.tenantReference, order, contextOverride)
        val service = GeneralAnalysisApplicationService(
            currentUserPort = object : CurrentUserPort {
                override fun currentUserId() = IdentityUserId("test-owner")
            },
            store = InMemoryGeneralAnalysisStore(),
            policyGuard = object : GeneralAnalysisPolicyGuard {
                override fun classify(question: String): PolicyClassification {
                    order += "policy"
                    return policyDelegate.classify(question)
                }
            },
            lensRouter = object : GeneralAnalysisLensRouter {
                override fun route(classification: PolicyClassification): GeneralAnalysisLens {
                    order += "lens"
                    return GeneralAnalysisLens.GENERAL_ANALYSIS
                }
            },
            executionPort = adapter(contextFactory, port),
            outputValidator = object : GeneralAnalysisOutputValidator {
                private val delegate = FinanceGeneralAnalysisOutputValidator()
                override fun isValid(candidate: GeneralAnalysisCandidate): Boolean {
                    order += "output-validator"
                    return delegate.isValid(candidate)
                }
            },
        )

        fun analyze(questionText: String, checkedIds: Set<String>): labs.ranikun.finance.generalanalysis.application.GeneralAnalysisView {
            val question = service.createQuestion(questionText)
            service.confirmChecklist(question.questionId, question.checklistId, checkedIds, true)
            return service.analyze(question.questionId, question.checklistId)
        }
    }

    private fun safePolicy() = object : GeneralAnalysisPolicyGuard {
        override fun classify(question: String) = PolicyClassification.SAFE_GENERAL
    }
}
