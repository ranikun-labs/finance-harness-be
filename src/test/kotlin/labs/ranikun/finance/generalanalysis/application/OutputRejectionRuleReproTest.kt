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
 * Local repro only (copy tree). Each synthetic 5-field answer breaks exactly one OUTPUT_REJECTED rule.
 * Expectation: outcome stays FAILED with no output; the failure log names the rule via detail=<RULE>.
 * Current code logs only `failureCode=OUTPUT_REJECTED`, so the rule cases are expected to fail on baseline.
 */
class OutputRejectionRuleReproTest {
    private val parser = FinanceGeneralAnalysisInterpretationParser()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val logger = LoggerFactory.getLogger(GeneralAnalysisFailureCode::class.java.name) as Logger

    @BeforeEach
    fun attach() {
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun json(
        summary: String = "Summary text.",
        observations: String = "[\"Observation one.\"]",
        assumptions: String = "[\"Assumption one.\"]",
        nextChecks: String = "[\"Check one.\"]",
        evidenceNote: String = "Evidence note.",
    ) = """{"summary":"$summary","observations":$observations,"assumptions":$assumptions,""" +
        """"nextChecks":$nextChecks,"evidenceNote":"$evidenceNote"}"""

    private fun runWith(candidate: GeneralAnalysisCandidate): Pair<GeneralAnalysisStatus, List<String>> {
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
            outputValidator = FinanceGeneralAnalysisOutputValidator(),
        )
        val setup = service.createQuestion("Explain the available information.")
        service.confirmChecklist(setup.questionId, setup.checklistId, emptySet(), true)
        val view = service.analyze(setup.questionId, setup.checklistId)
        val logs = appender.list.map { it.formattedMessage }
        return view.status to logs
    }

    private fun assertRejectedWithRule(interpretation: String, rule: String) {
        val candidate = parser.parse(interpretation)
        assertThat(candidate).`as`("parser must accept the synthetic answer so the validator is reached").isNotNull
        val (status, logs) = runWith(candidate!!)
        assertThat(status).isEqualTo(GeneralAnalysisStatus.FAILED)
        assertThat(logs).anySatisfy { line ->
            assertThat(line).contains("failureCode=OUTPUT_REJECTED").contains("detail=$rule")
        }
    }

    @Test
    fun valid_control_completes() {
        val candidate = parser.parse(json())
        val (status, logs) = runWith(candidate!!)
        assertThat(status).isEqualTo(GeneralAnalysisStatus.COMPLETED)
        assertThat(logs).isEmpty()
    }

    @Test
    fun summary_blank_maps_to_SUMMARY_OR_EVIDENCE_BLANK() =
        assertRejectedWithRule(json(summary = ""), "SUMMARY_OR_EVIDENCE_BLANK")

    @Test
    fun evidence_note_blank_maps_to_SUMMARY_OR_EVIDENCE_BLANK() =
        assertRejectedWithRule(json(evidenceNote = "   "), "SUMMARY_OR_EVIDENCE_BLANK")

    @Test
    fun field_over_2000_chars_maps_to_FIELD_TOO_LONG() =
        assertRejectedWithRule(json(observations = "[\"" + "a".repeat(2001) + "\"]"), "FIELD_TOO_LONG")

    @Test
    fun english_forecast_term_maps_to_UNSUPPORTED_FINANCIAL_CONTENT() =
        assertRejectedWithRule(json(observations = "[\"The forecast is unclear.\"]"), "UNSUPPORTED_FINANCIAL_CONTENT")

    @Test
    fun korean_price_term_maps_to_UNSUPPORTED_FINANCIAL_CONTENT() =
        assertRejectedWithRule(json(assumptions = "[\"주가 흐름은 가정임.\"]"), "UNSUPPORTED_FINANCIAL_CONTENT")

    @Test
    fun uppercase_ticker_after_action_maps_to_DIRECT_SHORT_SYMBOL_ACTION() =
        assertRejectedWithRule(json(nextChecks = "[\"Add NVDA to the list.\"]"), "DIRECT_SHORT_SYMBOL_ACTION")

    @Test
    fun ordinary_lowercase_word_after_add_also_maps_to_DIRECT_SHORT_SYMBOL_ACTION() =
        assertRejectedWithRule(json(nextChecks = "[\"Add notes to the checklist.\"]"), "DIRECT_SHORT_SYMBOL_ACTION")

    @Test
    fun trade_then_brokerage_maps_to_DIRECT_BROKER_ACTION() =
        assertRejectedWithRule(
            json(observations = "[\"Check the trade-off, then compare the brokerage fee.\"]"),
            "DIRECT_BROKER_ACTION",
        )

    @Test
    fun empty_array_never_reaches_validator_parser_rejects_it() {
        // Negative control: parser returns null, so the service logs INTERPRETATION_INVALID, not OUTPUT_REJECTED.
        assertThat(parser.parse(json(nextChecks = "[]"))).isNull()
    }
}
