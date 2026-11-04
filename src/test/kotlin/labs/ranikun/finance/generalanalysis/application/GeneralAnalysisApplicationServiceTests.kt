package labs.ranikun.finance.generalanalysis.application

import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisPolicyGuard
import labs.ranikun.finance.generalanalysis.adapter.InMemoryGeneralAnalysisStore
import labs.ranikun.finance.generalanalysis.domain.ChecklistResult
import labs.ranikun.finance.generalanalysis.domain.FinanceQuestion
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisRecord
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class GeneralAnalysisApplicationServiceTests {

    @Test
    fun confirmedChecklistRunsGuardThenLensThenExecutionThenValidation() {
        val setup = Harness()
        val question = setup.service.createQuestion("What facts should I verify?")
        setup.service.confirmChecklist(question.questionId, question.checklistId, setOf("known_facts"), true)

        val result = setup.service.analyze(question.questionId, question.checklistId)

        assertThat(result.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(result.output).isNotNull
        assertThat(setup.order).containsExactly("guard", "lens", "execution", "validation")
        assertThat(setup.execution.inputs.single().checkedItemIds).containsExactly("known_facts")
        assertThat(setup.store.savedAnalyses.map { it.status }).containsExactly(
            GeneralAnalysisStatus.REQUESTED,
            GeneralAnalysisStatus.RUNNING,
            GeneralAnalysisStatus.COMPLETED,
        )
    }

    @Test
    fun unconfirmedChecklistBlocksExecution() {
        val setup = Harness()
        val question = setup.service.createQuestion("How should I think about this?")

        assertThrows(ChecklistNotConfirmedException::class.java) {
            setup.service.analyze(question.questionId, question.checklistId)
        }

        assertThat(setup.execution.inputs).isEmpty()
        assertThat(setup.store.savedAnalyses).isEmpty()
    }

    @Test
    fun checklistForAnotherQuestionBlocksExecution() {
        val setup = Harness()
        val first = setup.service.createQuestion("First question")
        val second = setup.service.createQuestion("Second question")
        setup.service.confirmChecklist(second.questionId, second.checklistId, emptySet(), true)

        assertThrows(ChecklistQuestionMismatchException::class.java) {
            setup.service.analyze(first.questionId, second.checklistId)
        }

        assertThat(setup.execution.inputs).isEmpty()
        assertThat(setup.store.savedAnalyses).isEmpty()
    }

    @Test
    fun policyRefusalBlocksLensAndExecutionAndReturnsNoCandidate() {
        val setup = Harness()
        val question = setup.service.createQuestion("지금 사도 돼?")
        setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

        val result = setup.service.analyze(question.questionId, question.checklistId)

        assertThat(result.status).isEqualTo(GeneralAnalysisStatus.BLOCKED)
        assertThat(result.output).isNull()
        assertThat(setup.order).containsExactly("guard")
        assertThat(setup.execution.inputs).isEmpty()
    }

    @Test
    fun purchaseAndInvestmentRecommendationWordingBlocksBeforeLensAndExecution() {
        listOf(
            "Should I purchase shares?",
            "Should I sell these shares?",
            "Should I hold this position?",
            "Is this a good investment?",
            "Should I make an investment now?",
            "Give me an investment recommendation.",
            "Analyze why acquiring shares now is wise.",
            "How do I acquire shares?",
            "How do I acquire AAPL?",
            "How can we acquire aapl?",
            "How can we acquire aapl gradually?",
            "How can we acquire aapl through a broker?",
            "How can we acquire aapl slowly?",
            "How can we acquire aapl over time?",
            "Explain how to acquire AAPL slowly?",
            "Compare ways to accumulate TSLA?",
            "What are ways to invest in MSFT?",
            "Explain ways to accumulate TSLA?",
            "Compare how to acquire AAPL?",
            "What are steps to invest in MSFT?",
            "Explain how I can acquire AAPL?",
            "Explain the process of acquiring AAPL.",
            "How can we acquire KEY slowly?",
            "How can we acquire IT slowly?",
            "How can we acquire TEAM slowly?",
            "How can we acquire ON slowly?",
            "How can we acquire API slowly?",
            "How can we acquire SQL slowly?",
            "How can we acquire the aapl slowly?",
            "How can I acquire my aapl over time?",
            "How can we acquire the new advanced aapl slowly?",
            "How can we acquire fractional aapl over time?",
            "How do I invest in AAPL?",
            "How can I invest in aapl?",
            "How can I invest in gold?",
            "How do I acquire ${"details and relevant context ".repeat(5)} shares?",
            "How can I purchase AAPL common equity?",
            "Tell me how to accumulate shares through a broker.",
            "Analyze why acquiring AAPL common equity now is wise.",
            "Analyze why acquiring shares now makes sense.",
            "Analyze why acquiring shares now is a smart choice.",
            "Analyze why accumulating shares now makes sense.",
            "Would it be prudent to purchase stock this week?",
        ).forEach { riskyQuestion ->
            val setup = Harness(FinanceGeneralAnalysisPolicyGuard())
            val question = setup.service.createQuestion(riskyQuestion)
            setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

            val result = setup.service.analyze(question.questionId, question.checklistId)

            assertThat(result.status).describedAs(riskyQuestion).isEqualTo(GeneralAnalysisStatus.BLOCKED)
            assertThat(result.output).describedAs(riskyQuestion).isNull()
            assertThat(setup.order).describedAs(riskyQuestion).containsExactly("guard")
            assertThat(setup.execution.inputs).describedAs(riskyQuestion).isEmpty()
        }
    }

    @Test
    fun ambiguousUnknownIntentFailsClosedBeforeLensAndExecution() {
        listOf(
            "Should I get in now?",
            "Is now the right time?",
            "Should I make a move?",
            "Would taking a position in TSLA today be wise?",
            "Explain whether I should take a position in TSLA now?",
            "Explain how to acquire aapl slowly?",
            "Compare ways to accumulate tsla?",
            "What are steps to invest in msft?",
            "Explain how I can acquire aapl?",
            "Explain the process of acquiring aapl.",
        ).forEach { ambiguousQuestion ->
            val policyGuard = FinanceGeneralAnalysisPolicyGuard()
            assertThat(policyGuard.classify(ambiguousQuestion))
                .describedAs(ambiguousQuestion)
                .isEqualTo(PolicyClassification.UNKNOWN)

            val setup = Harness(policyGuard)
            val question = setup.service.createQuestion(ambiguousQuestion)
            setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

            val result = setup.service.analyze(question.questionId, question.checklistId)

            assertThat(result.status).describedAs(ambiguousQuestion).isEqualTo(GeneralAnalysisStatus.BLOCKED)
            assertThat(result.output).describedAs(ambiguousQuestion).isNull()
            assertThat(setup.order).describedAs(ambiguousQuestion).containsExactly("guard")
            assertThat(setup.execution.inputs).describedAs(ambiguousQuestion).isEmpty()
        }
    }

    @Test
    fun safeGeneralQuestionAllowlistPreservesInformationalAnalysis() {
        val policyGuard = FinanceGeneralAnalysisPolicyGuard()
        val questionText = "Explain how investing in gold works."
        assertThat(policyGuard.classify(questionText)).isEqualTo(PolicyClassification.SAFE_GENERAL)
        listOf(
            "What is stock investment?",
            "Explain how investing in bonds works.",
            "Explain how to acquire a new car?",
            "How can I verify this claim?",
            "How do I invest my time in learning?",
            "Compare ways to accumulate practical skills.",
            "What are ways to invest my time in learning?",
            "What are steps to invest my time in learning?",
            "Explain how to acquire an API key?",
            "Explain the process of acquiring API design skills.",
            "How can I acquire knowledge of budgeting?",
            "How can we acquire SQL skills?",
            "How can we acquire skills?",
            "How can we acquire skills slowly?",
            "How can we acquire new skills?",
            "How can we acquire new practical skills?",
            "How can we acquire more skills?",
            "How can we acquire lunch?",
            "How can we acquire key skills?",
            "How can we acquire KEY skills?",
            "How can we acquire fractional skills slowly?",
            "How can we acquire skills at work?",
            "How can we acquire IT skills?",
            "How can we acquire API skills?",
            "How can we acquire API design skills?",
        ).forEach { safeQuestion ->
            assertThat(policyGuard.classify(safeQuestion))
                .describedAs(safeQuestion)
                .isEqualTo(PolicyClassification.SAFE_GENERAL)
        }

        val setup = Harness(policyGuard)
        val question = setup.service.createQuestion(questionText)
        setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

        val result = setup.service.analyze(question.questionId, question.checklistId)

        assertThat(result.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(setup.order).containsExactly("guard", "lens", "execution", "validation")
        assertThat(setup.execution.inputs).hasSize(1)
    }

    @Test
    fun policyGuardExceptionFailsRequestWithoutRoutingOrExposingText() {
        val setup = Harness(object : GeneralAnalysisPolicyGuard {
            override fun classify(question: String): PolicyClassification =
                throw IllegalStateException("private classifier failure detail")
        })
        val question = setup.service.createQuestion("How can I verify this claim?")
        setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

        val result = setup.service.analyze(question.questionId, question.checklistId)

        assertThat(result.status).isEqualTo(GeneralAnalysisStatus.FAILED)
        assertThat(result.output).isNull()
        assertThat(setup.service.findAnalysis(result.analysisId).output).isNull()
        assertThat(setup.order).containsExactly("guard")
        assertThat(setup.execution.inputs).isEmpty()
        assertThat(setup.store.savedAnalyses.map { it.status }).containsExactly(
            GeneralAnalysisStatus.REQUESTED,
            GeneralAnalysisStatus.FAILED,
        )
    }

    @Test
    fun foreignOwnerCannotReadAnalysisAndReceivesNotFound() {
        val setup = Harness()
        val question = setup.service.createQuestion("How can I verify this claim?")
        setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)
        val analysis = setup.service.analyze(question.questionId, question.checklistId)
        setup.currentUser.userId = IdentityUserId("owner-b")

        assertThrows(AnalysisNotFoundException::class.java) {
            setup.service.findAnalysis(analysis.analysisId)
        }
        assertThrows(QuestionNotFoundException::class.java) {
            setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)
        }
        assertThrows(QuestionNotFoundException::class.java) {
            setup.service.findQuestion(question.questionId)
        }
        val ownerBQuestion = setup.service.createQuestion("Owner B question")
        assertThrows(ChecklistNotFoundException::class.java) {
            setup.service.confirmChecklist(
                ownerBQuestion.questionId,
                question.checklistId,
                emptySet(),
                true,
            )
        }
        assertThrows(ChecklistNotFoundException::class.java) {
            setup.service.analyze(ownerBQuestion.questionId, question.checklistId)
        }
    }

    @Test
    fun repeatedAnalysisRequestsExecuteAgainWithoutDedupeGuarantee() {
        val setup = Harness()
        val question = setup.service.createQuestion("How can I verify this information?")
        setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)

        val first = setup.service.analyze(question.questionId, question.checklistId)
        val second = setup.service.analyze(question.questionId, question.checklistId)

        assertThat(first.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(second.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(second.analysisId).isNotEqualTo(first.analysisId)
        assertThat(setup.execution.inputs).hasSize(2)
    }

    @Test
    fun freshProcessLocalStoreHasNoPriorQuestionChecklistOrAnalysisState() {
        val firstProcess = Harness()
        val question = firstProcess.service.createQuestion("How can I verify this information?")
        firstProcess.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)
        val analysis = firstProcess.service.analyze(question.questionId, question.checklistId)

        // A newly constructed process-local store does not restore the prior process state.
        val freshProcess = Harness()
        assertThrows(QuestionNotFoundException::class.java) {
            freshProcess.service.findQuestion(question.questionId)
        }
        assertThrows(AnalysisNotFoundException::class.java) {
            freshProcess.service.findAnalysis(analysis.analysisId)
        }
        assertThat(freshProcess.store.findChecklist(
            IdentityUserId("owner-a"),
            question.checklistId,
        )).isNull()
    }

    @Test
    fun technicalFailureAndMalformedOutputAreFailedWithoutExposingCandidate() {
        val technicalFailure = Harness().also { harness ->
            val question = harness.service.createQuestion("Explain the available information.")
            harness.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)
            harness.execution.result = GeneralAnalysisExecutionResult.TechnicalFailure
            val result = harness.service.analyze(question.questionId, question.checklistId)
            assertThat(result.status).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(result.output).isNull()
            assertThat(harness.service.findAnalysis(result.analysisId).output).isNull()
        }

        val malformed = Harness().also { harness ->
            val question = harness.service.createQuestion("Explain the available information.")
            harness.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)
            harness.execution.result = GeneralAnalysisExecutionResult.Success(
                GeneralAnalysisCandidate(
                    summary = "",
                    observations = listOf("untrusted candidate"),
                    assumptions = listOf("assumption"),
                    nextChecks = listOf("check"),
                    evidenceNote = "evidence",
                ),
            )
            val result = harness.service.analyze(question.questionId, question.checklistId)
            assertThat(result.status).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(result.output).isNull()
            assertThat(harness.service.findAnalysis(result.analysisId).output).isNull()
        }

        assertThat(technicalFailure.execution.inputs).hasSize(1)
        assertThat(malformed.execution.inputs).hasSize(1)
    }

    @Test
    fun prohibitedAdviceInCandidateFailsValidationWithoutExposingCandidate() {
        listOf(
            "You should buy now.",
            "Acquire AAPL shares through a broker.",
            "Acquire AAPL through a broker.",
            "Acquire aapl through a broker.",
            "Acquire aapl.",
            "Acquire aapl gradually.",
            "Acquire aapl slowly.",
            "Acquire aapl over time.",
            "Acquire KEY slowly.",
            "Acquire IT slowly.",
            "Acquire TEAM slowly.",
            "Acquire ON slowly.",
            "Acquire API slowly.",
            "Acquire SQL slowly.",
            "Acquire the aapl slowly.",
            "Acquire my aapl over time.",
            "Acquire the new advanced aapl slowly.",
            "Acquire fractional aapl over time.",
            "Acquire aapl for my portfolio over time.",
            "Acquire ${"details ".repeat(15)} shares through a broker.",
        ).forEach { prohibitedText ->
            val setup = Harness()
            val question = setup.service.createQuestion("Summarize the information available.")
            setup.service.confirmChecklist(question.questionId, question.checklistId, emptySet(), true)
            setup.execution.result = GeneralAnalysisExecutionResult.Success(
                GeneralAnalysisCandidate(
                    summary = prohibitedText,
                    observations = listOf("The question asks for a summary."),
                    assumptions = listOf("No external data was supplied."),
                    nextChecks = listOf("Verify facts with a primary source."),
                    evidenceNote = "Source: user-provided question only.",
                ),
            )

            val result = setup.service.analyze(question.questionId, question.checklistId)

            assertThat(result.status).describedAs(prohibitedText).isEqualTo(GeneralAnalysisStatus.FAILED)
            assertThat(result.output).describedAs(prohibitedText).isNull()
            assertThat(setup.service.findAnalysis(result.analysisId).output).describedAs(prohibitedText).isNull()
        }
    }

    @Test
    fun targetPricesPositionSizingReturnForecastsAndUnsupportedMarketFactsFailValidation() {
        val candidates = listOf(
            "The price target is 120.",
            "The position size should be 10%.",
            "Expected return is 12%.",
            "The stock price will rise.",
            "Acquiring shares now is wise.",
            "Acquire AAPL shares through a broker.",
            "Acquire AAPL through a broker.",
            "Acquire aapl through a broker.",
            "Acquire aapl.",
            "Acquire aapl gradually.",
            "Acquire aapl slowly.",
            "Acquire the new advanced aapl slowly.",
            "Acquire fractional aapl over time.",
            "Acquire shares through a broker.",
            "Acquire ${"details ".repeat(15)} shares through a broker.",
            "Acquiring AAPL common equity now is wise.",
            "Acquiring shares now makes sense.",
            "Acquiring shares now is a smart choice.",
            "Accumulating shares now makes sense.",
            "It may be prudent to purchase stock this week.",
            "수익률 예측은 연 12%입니다.",
            "The company's revenue grew 20% last quarter.",
        )
        val validator = FinanceGeneralAnalysisOutputValidator()

        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire skills slowly.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).describedAs("Acquire skills slowly").isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire new skills.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire API design skills.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire more skills.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire skills at work.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire IT skills.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire key skills.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()
        assertThat(
            validator.isValid(
                GeneralAnalysisCandidate(
                    summary = "Acquire KEY skills.",
                    observations = listOf("The question asks for learning guidance."),
                    assumptions = listOf("No financial instrument was named."),
                    nextChecks = listOf("Verify learning resources."),
                    evidenceNote = "No external source was consulted.",
                ),
            ),
        ).isTrue()

        candidates.forEach { unsupportedClaim ->
            assertThat(
                validator.isValid(
                    GeneralAnalysisCandidate(
                        summary = unsupportedClaim,
                        observations = listOf("Only the submitted question was provided as input."),
                        assumptions = listOf("No external data was supplied."),
                        nextChecks = listOf("Verify relevant facts with a primary source."),
                        evidenceNote = "Evidence: user-provided question only; no external source was consulted.",
                    ),
                ),
            ).describedAs(unsupportedClaim).isFalse()
        }
    }

    private class Harness(policyGuardDelegate: GeneralAnalysisPolicyGuard? = null) {
        val currentUser = MutableCurrentUserPort(IdentityUserId("owner-a"))
        val store = RecordingGeneralAnalysisStore()
        val order = mutableListOf<String>()
        val execution = RecordingExecutionPort(order)
        private val selectedPolicyGuard = policyGuardDelegate ?: object : GeneralAnalysisPolicyGuard {
            override fun classify(question: String): PolicyClassification =
                if (question.contains("사도")) {
                    PolicyClassification.RISK_DECISION
                } else {
                    PolicyClassification.SAFE_GENERAL
                }
        }
        val service = GeneralAnalysisApplicationService(
            currentUserPort = currentUser,
            store = store,
            policyGuard = object : GeneralAnalysisPolicyGuard {
                override fun classify(question: String): PolicyClassification {
                    order += "guard"
                    return selectedPolicyGuard.classify(question)
                }
            },
            lensRouter = object : GeneralAnalysisLensRouter {
                override fun route(classification: PolicyClassification): GeneralAnalysisLens {
                    order += "lens"
                    return GeneralAnalysisLens.GENERAL_ANALYSIS
                }
            },
            executionPort = execution,
            outputValidator = object : GeneralAnalysisOutputValidator {
                private val delegate = FinanceGeneralAnalysisOutputValidator()
                override fun isValid(candidate: GeneralAnalysisCandidate): Boolean {
                    order += "validation"
                    return delegate.isValid(candidate)
                }
            },
        )
    }

    private class MutableCurrentUserPort(var userId: IdentityUserId) : CurrentUserPort {
        override fun currentUserId(): IdentityUserId = userId
    }

    private class RecordingGeneralAnalysisStore : GeneralAnalysisStore {
        private val processLocalStore = InMemoryGeneralAnalysisStore()
        val savedAnalyses = mutableListOf<GeneralAnalysisRecord>()

        override fun saveQuestion(question: FinanceQuestion) {
            processLocalStore.saveQuestion(question)
        }

        override fun findQuestion(owner: IdentityUserId, questionId: UUID): FinanceQuestion? =
            processLocalStore.findQuestion(owner, questionId)

        override fun saveChecklist(checklist: ChecklistResult) {
            processLocalStore.saveChecklist(checklist)
        }

        override fun findChecklist(owner: IdentityUserId, checklistId: UUID): ChecklistResult? =
            processLocalStore.findChecklist(owner, checklistId)

        override fun saveAnalysis(analysis: GeneralAnalysisRecord) {
            processLocalStore.saveAnalysis(analysis)
            savedAnalyses += analysis
        }

        override fun findAnalysis(owner: IdentityUserId, analysisId: UUID): GeneralAnalysisRecord? =
            processLocalStore.findAnalysis(owner, analysisId)
    }

    private class RecordingExecutionPort(private val order: MutableList<String>) : GeneralAnalysisExecutionPort {
        val inputs = mutableListOf<GeneralAnalysisExecutionInput>()
        var result: GeneralAnalysisExecutionResult = GeneralAnalysisExecutionResult.Success(
            GeneralAnalysisCandidate(
                summary = "The question has been received for general analysis.",
                observations = listOf("Only the question was supplied."),
                assumptions = listOf("No external facts were supplied."),
                nextChecks = listOf("Check primary sources for relevant facts."),
                evidenceNote = "Source: user-provided question only.",
            ),
        )

        override fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult {
            order += "execution"
            inputs += input
            return result
        }
    }
}
