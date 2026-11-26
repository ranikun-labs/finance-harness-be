package labs.ranikun.finance.generalanalysis.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisInterpretationParser
import labs.ranikun.finance.generalanalysis.adapter.FinanceGeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.adapter.InMemoryGeneralAnalysisStore
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisStatus
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Formal regression test for the output-rejection rule code mapping.
 *
 * Mapping under test: GeneralAnalysisApplicationService.analyze() -> OutputValidator.rejectionRule()
 * -> GeneralAnalysisFailureCode.OUTPUT_REJECTED.log(analysisRequestId, startedAt, rule).
 *
 * Guarantees checked here:
 * - each rejection emits exactly ONE diagnostic line in the fixed format
 *   `analysisRequestId=<id> elapsedMs=<n> failureCode=OUTPUT_REJECTED detail=<RULE>`;
 * - the analysis ends FAILED and exposes no output;
 * - the diagnostic never contains raw question/answer text, exception messages, or keys
 *   (only the fixed enum name is logged).
 *
 * Multi-rule inputs are asserted against the first-match order of FinanceGeneralAnalysisOutputValidator.rejectionRule().
 */
class OutputRejectionRuleReproTest {
    private val parser = FinanceGeneralAnalysisInterpretationParser()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var rootAppender: ListAppender<ILoggingEvent>
    private val logger = LoggerFactory.getLogger(GeneralAnalysisFailureCode::class.java.name) as Logger
    private val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun attach() {
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        rootAppender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        rootLogger.addAppender(rootAppender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        rootLogger.detachAppender(rootAppender)
    }

    private data class Run(
        val view: GeneralAnalysisView,
        /** Formatted messages from the GeneralAnalysisFailureCode logger only. */
        val logs: List<String>,
        /** Formatted messages from every logger during the run (leakage scan). */
        val allLogs: List<String>,
    )

    private fun json(
        summary: String = "Summary text.",
        observations: String = "[\"Observation one.\"]",
        assumptions: String = "[\"Assumption one.\"]",
        nextChecks: String = "[\"Check one.\"]",
        evidenceNote: String = "Evidence note.",
    ) = """{"summary":"$summary","observations":$observations,"assumptions":$assumptions,""" +
        """"nextChecks":$nextChecks,"evidenceNote":"$evidenceNote"}"""

    private fun candidate(
        summary: String = "Summary text.",
        observations: List<String> = listOf("Observation one."),
        assumptions: List<String> = listOf("Assumption one."),
        nextChecks: List<String> = listOf("Check one."),
        evidenceNote: String = "Evidence note.",
    ) = GeneralAnalysisCandidate(summary, observations, assumptions, nextChecks, evidenceNote)

    private fun runWith(
        candidate: GeneralAnalysisCandidate,
        question: String = "Explain the available information.",
        validator: GeneralAnalysisOutputValidator = FinanceGeneralAnalysisOutputValidator(),
    ): Run {
        val service = GeneralAnalysisApplicationService(
            currentUserPort = object : CurrentUserPort {
                override fun currentUserId() = IdentityUserId("repro-owner")
            },
            store = InMemoryGeneralAnalysisStore(),
            policyGuard = object : GeneralAnalysisPolicyGuard {
                override fun classify(question: String) = PolicyClassification.SAFE_GENERAL
            },
            lensRouter = object : GeneralAnalysisLensRouter {
                override fun route(classification: PolicyClassification) = GeneralAnalysisLens.GENERAL_ANALYSIS
            },
            executionPort = object : GeneralAnalysisExecutionPort {
                override fun execute(input: GeneralAnalysisExecutionInput) =
                    GeneralAnalysisExecutionResult.Success(candidate)
            },
            outputValidator = validator,
        )
        val setup = service.createQuestion(question)
        service.confirmChecklist(setup.questionId, setup.checklistId, emptySet(), true)
        val view = service.analyze(setup.questionId, setup.checklistId)
        return Run(
            view = view,
            logs = appender.list.map { it.formattedMessage },
            allLogs = rootAppender.list.map { it.formattedMessage },
        )
    }

    /** Exactly one line, fixed format, FAILED, no output. */
    private fun assertOneRejectionLine(run: Run, rule: OutputRejectionRule) {
        assertThat(run.view.status).isEqualTo(GeneralAnalysisStatus.FAILED)
        assertThat(run.view.output).isNull()
        assertThat(run.logs).`as`("exactly one diagnostic line for one rejected analysis").hasSize(1)
        assertThat(run.logs.single())
            .matches("analysisRequestId=${run.view.analysisId} elapsedMs=[0-9]+ failureCode=OUTPUT_REJECTED detail=${rule.name}")
    }

    private fun assertRejectedWithRule(interpretation: String, rule: OutputRejectionRule) {
        val candidate = parser.parse(interpretation)
        assertThat(candidate).`as`("parser must accept the synthetic answer so the validator is reached").isNotNull
        assertOneRejectionLine(runWith(candidate!!), rule)
    }

    private fun assertRejectedCandidate(candidate: GeneralAnalysisCandidate, rule: OutputRejectionRule) =
        assertOneRejectionLine(runWith(candidate), rule)

    @Test
    fun valid_control_completes_with_output_and_no_log() {
        val run = runWith(parser.parse(json())!!)
        assertThat(run.view.status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(run.view.output).isNotNull
        assertThat(run.logs).isEmpty()
    }

    // --- Single-rule cases (one per OutputRejectionRule value that the validator can produce) ---

    @Test
    fun summary_blank_maps_to_SUMMARY_OR_EVIDENCE_BLANK() =
        assertRejectedWithRule(json(summary = ""), OutputRejectionRule.SUMMARY_OR_EVIDENCE_BLANK)

    @Test
    fun evidence_note_blank_maps_to_SUMMARY_OR_EVIDENCE_BLANK() =
        assertRejectedWithRule(json(evidenceNote = "   "), OutputRejectionRule.SUMMARY_OR_EVIDENCE_BLANK)

    @Test
    fun empty_observations_maps_to_EMPTY_ARRAY() =
        assertRejectedCandidate(candidate(observations = emptyList()), OutputRejectionRule.EMPTY_ARRAY)

    @Test
    fun empty_assumptions_maps_to_EMPTY_ARRAY() =
        assertRejectedCandidate(candidate(assumptions = emptyList()), OutputRejectionRule.EMPTY_ARRAY)

    @Test
    fun empty_next_checks_maps_to_EMPTY_ARRAY() =
        assertRejectedCandidate(candidate(nextChecks = emptyList()), OutputRejectionRule.EMPTY_ARRAY)

    @Test
    fun blank_array_item_maps_to_FIELD_BLANK() =
        assertRejectedCandidate(candidate(observations = listOf("   ")), OutputRejectionRule.FIELD_BLANK)

    @Test
    fun field_over_2000_chars_maps_to_FIELD_TOO_LONG() =
        assertRejectedWithRule(
            json(observations = "[\"" + "a".repeat(2001) + "\"]"),
            OutputRejectionRule.FIELD_TOO_LONG,
        )

    @Test
    fun english_forecast_term_maps_to_UNSUPPORTED_FINANCIAL_CONTENT() =
        assertRejectedWithRule(
            json(observations = "[\"The forecast is unclear.\"]"),
            OutputRejectionRule.UNSUPPORTED_FINANCIAL_CONTENT,
        )

    @Test
    fun korean_price_term_maps_to_UNSUPPORTED_FINANCIAL_CONTENT() =
        assertRejectedWithRule(
            json(assumptions = "[\"주가 흐름은 가정임.\"]"),
            OutputRejectionRule.UNSUPPORTED_FINANCIAL_CONTENT,
        )

    @Test
    fun uppercase_ticker_after_action_maps_to_DIRECT_SHORT_SYMBOL_ACTION() =
        assertRejectedWithRule(
            json(nextChecks = "[\"Add NVDA to the list.\"]"),
            OutputRejectionRule.DIRECT_SHORT_SYMBOL_ACTION,
        )

    @Test
    fun ordinary_lowercase_word_after_add_also_maps_to_DIRECT_SHORT_SYMBOL_ACTION() =
        assertRejectedWithRule(
            json(nextChecks = "[\"Add notes to the checklist.\"]"),
            OutputRejectionRule.DIRECT_SHORT_SYMBOL_ACTION,
        )

    @Test
    fun trade_then_brokerage_maps_to_DIRECT_BROKER_ACTION() =
        assertRejectedWithRule(
            json(observations = "[\"Check the trade-off, then compare the brokerage fee.\"]"),
            OutputRejectionRule.DIRECT_BROKER_ACTION,
        )

    @Test
    fun validator_reporting_unclassified_maps_to_UNCLASSIFIED() {
        // Uses the interface default rejectionRule(): isValid=false yields UNCLASSIFIED.
        val failingValidator = object : GeneralAnalysisOutputValidator {
            override fun isValid(candidate: GeneralAnalysisCandidate): Boolean = false
        }
        assertOneRejectionLine(runWith(candidate(), validator = failingValidator), OutputRejectionRule.UNCLASSIFIED)
    }

    @Test
    fun validator_exception_maps_to_VALIDATOR_EXCEPTION_without_leaking_raw_text_or_key() {
        val syntheticKey = "sk-test-SYNTHETIC-0000"
        val syntheticQuestion = "SYNTHETIC-QUESTION-0000 explain the holdings."
        val syntheticAnswer = "SYNTHETIC-ANSWER-0000"
        val throwingValidator = object : GeneralAnalysisOutputValidator {
            override fun isValid(candidate: GeneralAnalysisCandidate): Boolean = error("not called by the service")
            override fun rejectionRule(candidate: GeneralAnalysisCandidate): OutputRejectionRule? =
                throw IllegalStateException(
                    "validator failed key=$syntheticKey question=$syntheticQuestion answer=${candidate.summary}",
                )
        }

        val run = runWith(
            candidate(summary = "$syntheticAnswer summary text."),
            question = syntheticQuestion,
            validator = throwingValidator,
        )

        assertOneRejectionLine(run, OutputRejectionRule.VALIDATOR_EXCEPTION)
        assertThat(run.allLogs.joinToString("\n"))
            .doesNotContain(syntheticKey, syntheticQuestion, syntheticAnswer, "validator failed")
    }

    // --- Multi-rule priority cases ---
    // Source order (FinanceGeneralAnalysisOutputValidator.rejectionRule(), first match wins):
    //   line 18  SUMMARY_OR_EVIDENCE_BLANK
    //   line 19-21 EMPTY_ARRAY
    //   line 22  FIELD_BLANK
    //   line 23  FIELD_TOO_LONG
    //   line 24-26 UNSUPPORTED_FINANCIAL_CONTENT
    //   line 27-29 DIRECT_SHORT_SYMBOL_ACTION
    //   line 30  DIRECT_BROKER_ACTION

    @Test
    fun priority_SUMMARY_OR_EVIDENCE_BLANK_beats_EMPTY_ARRAY() =
        assertRejectedCandidate(
            candidate(summary = "", observations = emptyList()),
            OutputRejectionRule.SUMMARY_OR_EVIDENCE_BLANK,
        )

    @Test
    fun priority_all_five_violations_still_report_SUMMARY_OR_EVIDENCE_BLANK() =
        assertRejectedCandidate(
            candidate(
                summary = "",
                observations = listOf("The forecast is unclear.", "Check the trade-off, then compare the brokerage fee."),
                assumptions = listOf("a".repeat(2001)),
                nextChecks = listOf("Add NVDA to the list."),
            ),
            OutputRejectionRule.SUMMARY_OR_EVIDENCE_BLANK,
        )

    @Test
    fun priority_EMPTY_ARRAY_beats_FIELD_BLANK() =
        assertRejectedCandidate(
            candidate(observations = emptyList(), assumptions = listOf(" ")),
            OutputRejectionRule.EMPTY_ARRAY,
        )

    @Test
    fun priority_FIELD_BLANK_beats_FIELD_TOO_LONG() =
        assertRejectedCandidate(
            candidate(observations = listOf(" "), assumptions = listOf("a".repeat(2001))),
            OutputRejectionRule.FIELD_BLANK,
        )

    @Test
    fun priority_FIELD_TOO_LONG_beats_UNSUPPORTED_FINANCIAL_CONTENT() =
        assertRejectedCandidate(
            candidate(observations = listOf("The forecast is unclear. " + "a".repeat(2000))),
            OutputRejectionRule.FIELD_TOO_LONG,
        )

    @Test
    fun priority_UNSUPPORTED_FINANCIAL_CONTENT_beats_DIRECT_SHORT_SYMBOL_ACTION() =
        assertRejectedCandidate(
            candidate(observations = listOf("The forecast is unclear."), nextChecks = listOf("Add NVDA to the list.")),
            OutputRejectionRule.UNSUPPORTED_FINANCIAL_CONTENT,
        )

    @Test
    fun priority_UNSUPPORTED_FINANCIAL_CONTENT_beats_DIRECT_BROKER_ACTION() =
        assertRejectedCandidate(
            candidate(
                observations = listOf("The forecast is unclear."),
                nextChecks = listOf("Check the trade-off, then compare the brokerage fee."),
            ),
            OutputRejectionRule.UNSUPPORTED_FINANCIAL_CONTENT,
        )

    @Test
    fun priority_DIRECT_SHORT_SYMBOL_ACTION_beats_DIRECT_BROKER_ACTION() =
        assertRejectedCandidate(
            candidate(
                observations = listOf("Check the trade-off, then compare the brokerage fee."),
                nextChecks = listOf("Add NVDA to the list."),
            ),
            OutputRejectionRule.DIRECT_SHORT_SYMBOL_ACTION,
        )

    // --- Coverage guard and negative control ---

    @Test
    fun rule_enum_values_match_the_cases_covered_in_this_class() {
        // If a rule is added to OutputRejectionRule, add a case above and extend this list.
        assertThat(OutputRejectionRule.entries.map { it.name }).containsExactly(
            "SUMMARY_OR_EVIDENCE_BLANK",
            "EMPTY_ARRAY",
            "FIELD_BLANK",
            "FIELD_TOO_LONG",
            "UNSUPPORTED_FINANCIAL_CONTENT",
            "DIRECT_SHORT_SYMBOL_ACTION",
            "DIRECT_BROKER_ACTION",
            "VALIDATOR_EXCEPTION",
            "UNCLASSIFIED",
        )
    }

    @Test
    fun empty_array_never_reaches_validator_parser_rejects_it() {
        // Negative control: parser returns null, so the service logs INTERPRETATION_INVALID, not OUTPUT_REJECTED.
        assertThat(parser.parse(json(nextChecks = "[]"))).isNull()
    }
}
