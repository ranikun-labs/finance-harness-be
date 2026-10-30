package labs.ranikun.finance.generalanalysis.consumer

import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisPolicyGuard
import labs.ranikun.finance.generalanalysis.adapter.InMemoryGeneralAnalysisStore
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisApplicationService
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

class FinanceGeneralAnalysisLogicalConsumerContractTests {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `maps Finance input to the exact test fixture context skill and canonical query before Finance validation`() {
        val fixture = fixture()
        val order = mutableListOf<String>()
        val consumer = RecordingLogicalConsumer(fixture.context, order)
        val candidate = safeCandidate()
        val adapter = adapter(fixture, consumer, order, candidate)
        val service = service(adapter, safePolicy(), order)
        val question = service.createQuestion(fixture.question)
        service.confirmChecklist(question.questionId, question.checklistId, fixture.checkedItemIds, true)

        val analysis = service.analyze(question.questionId, question.checklistId)

        assertThat(analysis.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(analysis.output).isEqualTo(candidate)
        assertThat(consumer.commands).hasSize(1)
        val command = consumer.commands.single()
        assertThat(command.context).isEqualTo(fixture.context)
        assertThat(command.context.scope.productReference).isEqualTo("finance")
        assertThat(command.context.scope.tenantReference).isEqualTo("tenant:fixture-1")
        assertThat(command.context.scope.resourceReferences)
            .containsExactly("checklist:fixture-1", "question:fixture-1")
        assertThat(command.context.principalReference).isEqualTo("principal:fixture-1")
        assertThat(command.context.requestReference).isEqualTo("request:fixture-1")
        assertThat(command.context.correlationReference).isEqualTo("correlation:fixture-1")
        assertThat(command.context.authorityReference).isEqualTo("authority:fixture-1")
        assertThat(command.skillId).isEqualTo(fixture.skillId).isEqualTo("finance.general-analysis@1")
        assertThat(command.query).isEqualTo(fixture.sharedAiQuery)
        assertThat(mapper.readTree(command.query)).isEqualTo(fixture.businessInputNode)
        assertThat(order).containsExactly("policy", "lens", "consumer", "candidate-translator", "finance-output-validator")
        val sharedResult = consumer.results.single() as LogicalConsumerResultFixture.Success
        assertThat(sharedResult.executionReference).isEqualTo("execution:issued-by-shared-ai")
        assertThat(sharedResult.evidence.context.executionReference).isEqualTo(sharedResult.executionReference)
        assertThat(sharedResult.evidence.context.contextVersion).isEqualTo("1")
    }

    @Test
    fun `rejects technical failure thrown consumer and invalid execution results without returning candidate text`() {
        val fixture = fixture()
        val technical = LogicalConsumerResultFixture.TechnicalFailure(
            executionReference = "execution:issued-by-shared-ai",
            category = "MODEL_FAILURE",
        )
        assertFailedWithoutCandidate(fixture) { RecordingLogicalConsumer(fixture.context, result = technical) }

        assertFailedWithoutCandidate(fixture) {
            RecordingLogicalConsumer(fixture.context) { throw IllegalStateException("private provider detail") }
        }

        val valid = success(fixture.context)
        val invalidResults = listOf(
            valid.copy(executionReference = "execution:other"),
            valid.copy(
                evidence = valid.evidence.copy(
                    context = valid.evidence.context.copy(correlationReference = "correlation:other"),
                ),
            ),
            valid.copy(
                evidence = valid.evidence.copy(
                    context = valid.evidence.context.copy(authorityReference = "authority:other"),
                ),
            ),
            valid.copy(
                evidence = valid.evidence.copy(
                    context = valid.evidence.context.copy(contextVersion = "2"),
                ),
            ),
            valid.copy(
                evidence = valid.evidence.copy(
                    context = valid.evidence.context.copy(principalReference = "principal:other"),
                ),
            ),
            valid.copy(
                evidence = valid.evidence.copy(
                    context = valid.evidence.context.copy(requestReference = "request:other"),
                ),
            ),
            valid.copy(
                evidence = valid.evidence.copy(
                    items = listOf(valid.evidence.items.single().copy(executionReference = "execution:other")),
                ),
            ),
            valid.copy(
                evidence = valid.evidence.copy(
                    items = listOf(valid.evidence.items.single().copy(
                        scope = fixture.context.scope.copy(tenantReference = "tenant:other"),
                    )),
                ),
            ),
            valid.copy(interpretation = "  "),
            valid.copy(evidence = valid.evidence.copy(items = emptyList())),
        )
        invalidResults.forEach { result ->
            assertFailedWithoutCandidate(fixture) { RecordingLogicalConsumer(fixture.context, result = result) }
        }
    }

    @Test
    fun `unknown and risky Finance policy blocks before the logical consumer`() {
        listOf(
            "Should I act on this?",
            "Should I purchase shares?",
        ).forEach { prompt ->
            val fixture = fixture(question = prompt)
            val order = mutableListOf<String>()
            val consumer = RecordingLogicalConsumer(fixture.context, order)
            val service = service(adapter(fixture, consumer, order, safeCandidate()), FinanceGeneralAnalysisPolicyGuard(), order)
            val question = service.createQuestion(prompt)
            service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

            val analysis = service.analyze(question.questionId, question.checklistId)

            assertThat(analysis.status).describedAs(prompt).isEqualTo(GeneralAnalysisStatus.BLOCKED)
            assertThat(analysis.output).describedAs(prompt).isNull()
            assertThat(consumer.commands).describedAs(prompt).isEmpty()
            assertThat(order).describedAs(prompt).containsExactly("policy")
        }
    }

    @Test
    fun `repeated analysis invokes the logical consumer again without deduplication`() {
        val fixture = fixture()
        val order = mutableListOf<String>()
        val consumer = RecordingLogicalConsumer(fixture.context, order)
        val adapter = adapter(fixture, consumer, order, safeCandidate())
        val service = service(adapter, safePolicy(), order)
        val question = service.createQuestion(fixture.question)
        service.confirmChecklist(question.questionId, question.checklistId, fixture.checkedItemIds, true)

        val first = service.analyze(question.questionId, question.checklistId)
        val second = service.analyze(question.questionId, question.checklistId)

        assertThat(first.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(second.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(second.analysisId).isNotEqualTo(first.analysisId)
        assertThat(consumer.commands).hasSize(2)
        assertThat(consumer.commands[1]).isEqualTo(consumer.commands[0])
        assertThat(order.count { it == "consumer" }).isEqualTo(2)
        assertThat(order.count { it == "candidate-translator" }).isEqualTo(2)
    }

    @Test
    fun `Finance output validator rejects translated prohibited candidate without exposing it`() {
        val fixture = fixture()
        val order = mutableListOf<String>()
        val consumer = RecordingLogicalConsumer(fixture.context, order)
        val prohibitedCandidate = safeCandidate().copy(summary = "Acquiring shares now is wise.")
        val adapter = adapter(fixture, consumer, order, prohibitedCandidate)
        val service = service(adapter, safePolicy(), order)
        val question = service.createQuestion(fixture.question)
        service.confirmChecklist(question.questionId, question.checklistId, fixture.checkedItemIds, true)

        val analysis = service.analyze(question.questionId, question.checklistId)

        assertThat(analysis.status).isEqualTo(GeneralAnalysisStatus.FAILED)
        assertThat(analysis.output).isNull()
        assertThat(service.findAnalysis(analysis.analysisId).output).isNull()
        assertThat(order).containsExactly("policy", "lens", "consumer", "candidate-translator", "finance-output-validator")
    }

    private fun assertFailedWithoutCandidate(
        fixture: ContractFixture,
        consumerFactory: () -> RecordingLogicalConsumer,
    ) {
        val order = mutableListOf<String>()
        val consumer = consumerFactory()
        consumer.onInvocation = { order += "consumer" }
        val mappedCandidates = mutableListOf<GeneralAnalysisCandidate>()
        val adapter = FinanceGeneralAnalysisLogicalConsumerFixtureAdapter(
            contextFixture = fixture.context,
            consumer = consumer,
            candidateTranslator = FinanceCandidateTranslatorFixture { _, _ ->
                order += "candidate-translator"
                safeCandidate().also(mappedCandidates::add)
            },
        )
        val service = service(adapter, safePolicy(), order)
        val question = service.createQuestion(fixture.question)
        service.confirmChecklist(question.questionId, question.checklistId, fixture.checkedItemIds, true)

        val analysis = service.analyze(question.questionId, question.checklistId)

        assertThat(analysis.status).isEqualTo(GeneralAnalysisStatus.FAILED)
        assertThat(analysis.output).isNull()
        assertThat(service.findAnalysis(analysis.analysisId).output).isNull()
        assertThat(mappedCandidates).isEmpty()
        assertThat(order).doesNotContain("candidate-translator")
    }

    private fun adapter(
        fixture: ContractFixture,
        consumer: LogicalSharedAiConsumerFixture,
        order: MutableList<String>,
        candidate: GeneralAnalysisCandidate,
    ) = FinanceGeneralAnalysisLogicalConsumerFixtureAdapter(
        contextFixture = fixture.context,
        consumer = consumer,
        candidateTranslator = FinanceCandidateTranslatorFixture { _, _ ->
            order += "candidate-translator"
            candidate
        },
    )

    private fun service(
        executionPort: GeneralAnalysisExecutionPort,
        policyGuard: GeneralAnalysisPolicyGuard,
        order: MutableList<String>,
    ): GeneralAnalysisApplicationService {
        val userId = IdentityUserId("test-owner")
        return GeneralAnalysisApplicationService(
            currentUserPort = object : CurrentUserPort {
                override fun currentUserId() = userId
            },
            store = InMemoryGeneralAnalysisStore(),
            policyGuard = object : GeneralAnalysisPolicyGuard {
                override fun classify(question: String): PolicyClassification {
                    order += "policy"
                    return policyGuard.classify(question)
                }
            },
            lensRouter = object : GeneralAnalysisLensRouter {
                override fun route(classification: PolicyClassification): GeneralAnalysisLens {
                    order += "lens"
                    return GeneralAnalysisLens.GENERAL_ANALYSIS
                }
            },
            executionPort = executionPort,
            outputValidator = object : GeneralAnalysisOutputValidator {
                private val delegate = FinanceGeneralAnalysisOutputValidator()
                override fun isValid(candidate: GeneralAnalysisCandidate): Boolean {
                    order += "finance-output-validator"
                    return delegate.isValid(candidate)
                }
            },
        )
    }

    private fun safePolicy() = object : GeneralAnalysisPolicyGuard {
        override fun classify(question: String) = PolicyClassification.SAFE_GENERAL
    }

    private fun fixture(question: String? = null): ContractFixture {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/finance-general-analysis-consumer-fixture.json"))
            .use { it.readBytes() }
        assertThat(bytes.last()).isEqualTo('\n'.code.toByte())
        val root = mapper.readTree(String(bytes, UTF_8))
        assertThat(root["fixtureVersion"].asText()).isEqualTo("finance-general-analysis-test-v2")
        assertThat(root["context"].has("executionReference")).isFalse()
        assertThat(root["context"].has("contextVersion")).isFalse()
        val scope = root["context"]["scope"]
        val context = LogicalExecutionContextSeedFixture(
            scope = LogicalExecutionScopeFixture(
                productReference = scope["productReference"].asText(),
                tenantReference = scope["tenantReference"].asText(),
                resourceReferences = listOf(
                    scope["resourceReferences"].get(0).asText(),
                    scope["resourceReferences"].get(1).asText(),
                ),
            ),
            principalReference = root["context"]["principalReference"].asText(),
            requestReference = root["context"]["requestReference"].asText(),
            correlationReference = root["context"]["correlationReference"].asText(),
            authorityReference = root["context"]["authorityReference"].asText(),
        )
        val businessInput = root["businessInput"]
        val checkedItemIds = listOf(
            businessInput["checkedChecklistItemIds"].get(0).asText(),
            businessInput["checkedChecklistItemIds"].get(1).asText(),
        )
        return ContractFixture(
            context = context,
            question = question ?: businessInput["question"].asText(),
            checkedItemIds = checkedItemIds.toSet(),
            skillId = root["skillId"].asText(),
            sharedAiQuery = root["sharedAiQuery"].asText(),
            businessInputNode = businessInput,
        )
    }

    private fun success(context: LogicalExecutionContextSeedFixture): LogicalConsumerResultFixture.Success {
        val executionReference = "execution:issued-by-shared-ai"
        val resultContext = LogicalExecutionContextFixture(
            scope = context.scope,
            principalReference = context.principalReference,
            requestReference = context.requestReference,
            executionReference = executionReference,
            correlationReference = context.correlationReference,
            contextVersion = "1",
            authorityReference = context.authorityReference,
        )
        return LogicalConsumerResultFixture.Success(
            executionReference = executionReference,
            interpretation = "synthetic fixture interpretation",
            evidence = LogicalEvidenceFixture(
                context = resultContext,
                items = listOf(
                    LogicalEvidenceItemFixture(
                        evidenceId = "evidence:fixture-1",
                        executionReference = executionReference,
                        scope = resultContext.scope,
                        sourceReferences = setOf("source:fixture-1"),
                    ),
                ),
            ),
        )
    }

    private fun safeCandidate() = GeneralAnalysisCandidate(
        summary = "The synthetic question is ready for a general review.",
        observations = listOf("Only the synthetic question was supplied."),
        assumptions = listOf("No external facts were supplied."),
        nextChecks = listOf("Verify relevant facts with a primary source."),
        evidenceNote = "Evidence: synthetic fixture only.",
    )

    private data class ContractFixture(
        val context: LogicalExecutionContextSeedFixture,
        val question: String,
        val checkedItemIds: Set<String>,
        val skillId: String,
        val sharedAiQuery: String,
        val businessInputNode: JsonNode,
    )

    private inner class RecordingLogicalConsumer(
        private val expectedContext: LogicalExecutionContextSeedFixture,
        private val order: MutableList<String> = mutableListOf(),
        private val result: LogicalConsumerResultFixture = success(expectedContext),
        private val behavior: (() -> LogicalConsumerResultFixture)? = null,
    ) : LogicalSharedAiConsumerFixture {
        val commands = mutableListOf<LogicalConsumerCommandFixture>()
        val results = mutableListOf<LogicalConsumerResultFixture>()
        var onInvocation: (() -> Unit)? = null

        override fun execute(command: LogicalConsumerCommandFixture): LogicalConsumerResultFixture {
            commands += command
            order += "consumer"
            onInvocation?.invoke()
            return (behavior?.invoke() ?: result).also(results::add)
        }
    }
}
