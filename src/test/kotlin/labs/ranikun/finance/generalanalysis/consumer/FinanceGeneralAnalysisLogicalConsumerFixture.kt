package labs.ranikun.finance.generalanalysis.consumer

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLens
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import tools.jackson.databind.json.JsonMapper

/**
 * Test-only logical seam for comparing Finance's consumer mapping with Shared AI's native contract tests.
 * These fixture types are not production DTOs or an approved transport schema.
 */
internal data class LogicalExecutionScopeFixture(
    val productReference: String,
    val tenantReference: String,
    val resourceReferences: List<String>,
)

/** Product-owned request seed; executionReference and contextVersion are supplied by Shared AI. */
internal data class LogicalExecutionContextSeedFixture(
    val scope: LogicalExecutionScopeFixture,
    val principalReference: String,
    val requestReference: String,
    val correlationReference: String?,
    val authorityReference: String?,
)

internal data class LogicalExecutionContextFixture(
    val scope: LogicalExecutionScopeFixture,
    val principalReference: String,
    val requestReference: String,
    val executionReference: String,
    val correlationReference: String?,
    val contextVersion: String,
    val authorityReference: String?,
)

internal data class LogicalConsumerCommandFixture(
    val context: LogicalExecutionContextSeedFixture,
    val skillId: String,
    val query: String,
)

internal data class LogicalEvidenceItemFixture(
    val evidenceId: String,
    val executionReference: String,
    val scope: LogicalExecutionScopeFixture,
    val sourceReferences: Set<String>,
)

internal data class LogicalEvidenceFixture(
    val context: LogicalExecutionContextFixture,
    val items: List<LogicalEvidenceItemFixture>,
)

internal sealed interface LogicalConsumerResultFixture {
    data class Success(
        val executionReference: String,
        val interpretation: String,
        val evidence: LogicalEvidenceFixture,
    ) : LogicalConsumerResultFixture

    data class TechnicalFailure(
        val executionReference: String,
        val category: String,
    ) : LogicalConsumerResultFixture
}

internal fun interface LogicalSharedAiConsumerFixture {
    fun execute(command: LogicalConsumerCommandFixture): LogicalConsumerResultFixture
}

internal fun interface FinanceCandidateTranslatorFixture {
    fun translate(interpretation: String, evidence: LogicalEvidenceFixture): GeneralAnalysisCandidate?
}

/** Local Finance-side fixture adapter only; it never calls Shared AI or a provider. */
internal class FinanceGeneralAnalysisLogicalConsumerFixtureAdapter(
    private val contextFixture: LogicalExecutionContextSeedFixture,
    private val consumer: LogicalSharedAiConsumerFixture,
    private val candidateTranslator: FinanceCandidateTranslatorFixture,
) : GeneralAnalysisExecutionPort {
    private val mapper = JsonMapper.builder().build()

    override fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult {
        val command = commandFor(input) ?: return GeneralAnalysisExecutionResult.TechnicalFailure
        val result = try {
            consumer.execute(command)
        } catch (_: Exception) {
            return GeneralAnalysisExecutionResult.TechnicalFailure
        }

        return when (result) {
            is LogicalConsumerResultFixture.TechnicalFailure -> GeneralAnalysisExecutionResult.TechnicalFailure
            is LogicalConsumerResultFixture.Success -> {
                if (!isCorrelatedAndValid(command.context, result)) {
                    return GeneralAnalysisExecutionResult.TechnicalFailure
                }
                val candidate = try {
                    candidateTranslator.translate(result.interpretation, result.evidence)
                } catch (_: Exception) {
                    null
                }
                if (candidate == null) {
                    GeneralAnalysisExecutionResult.TechnicalFailure
                } else {
                    GeneralAnalysisExecutionResult.Success(candidate)
                }
            }
        }
    }

    fun commandFor(input: GeneralAnalysisExecutionInput): LogicalConsumerCommandFixture? {
        val lensRef = when (input.lens) {
            GeneralAnalysisLens.GENERAL_ANALYSIS -> GENERAL_ANALYSIS_LENS_REF
        }
        val query = canonicalBusinessInputQuery(input, lensRef)
        return LogicalConsumerCommandFixture(
            context = contextFixture,
            skillId = FINANCE_GENERAL_ANALYSIS_SKILL_ID,
            query = query,
        )
    }

    private fun canonicalBusinessInputQuery(
        input: GeneralAnalysisExecutionInput,
        lensRef: String,
    ): String {
        val json = mapper.createObjectNode()
        json.put("question", input.question)
        val checkedIds = json.putArray("checkedChecklistItemIds")
        input.checkedItemIds.sorted().forEach(checkedIds::add)
        json.put("lensRef", lensRef)
        return mapper.writeValueAsString(json)
    }

    private fun isCorrelatedAndValid(
        expectedContext: LogicalExecutionContextSeedFixture,
        result: LogicalConsumerResultFixture.Success,
    ): Boolean {
        val evidenceIds = result.evidence.items.map(LogicalEvidenceItemFixture::evidenceId)
        val returnedContext = result.evidence.context
        return result.executionReference.isNotBlank() &&
            result.executionReference == returnedContext.executionReference &&
            result.interpretation.isNotBlank() &&
            returnedContext.contextVersion == SHARED_AI_CONTEXT_VERSION &&
            returnedContext.scope == expectedContext.scope &&
            returnedContext.principalReference == expectedContext.principalReference &&
            returnedContext.requestReference == expectedContext.requestReference &&
            returnedContext.correlationReference == expectedContext.correlationReference &&
            returnedContext.authorityReference == expectedContext.authorityReference &&
            result.evidence.items.isNotEmpty() &&
            evidenceIds.all(String::isNotBlank) &&
            evidenceIds.distinct().size == evidenceIds.size &&
            result.evidence.items.all { item ->
                item.executionReference == result.executionReference &&
                    item.scope == returnedContext.scope &&
                    item.sourceReferences.isNotEmpty() &&
                    item.sourceReferences.none(String::isBlank)
            }
    }

    private companion object {
        const val FINANCE_GENERAL_ANALYSIS_SKILL_ID = "finance.general-analysis@1"
        const val GENERAL_ANALYSIS_LENS_REF = "general-analysis"
        const val SHARED_AI_CONTEXT_VERSION = "1"
    }
}
