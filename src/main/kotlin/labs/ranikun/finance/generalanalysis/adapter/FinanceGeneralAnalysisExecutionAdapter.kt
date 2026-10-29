package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.FinanceExecutionContextFactory
import labs.ranikun.finance.generalanalysis.application.FinanceGeneralAnalysisBusinessInput
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiExecutionContext
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiEvidenceEnvelope
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiFailureCategory
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiOutcome
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiPort
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequest
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequestContext
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLens
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Finance-owned deterministic plain-text formatter for the existing Shared AI query field. */
class FinanceGeneralAnalysisQueryFormatter {
    fun format(input: FinanceGeneralAnalysisBusinessInput): String {
        require(input.lensRef == GENERAL_ANALYSIS_LENS_REF) { "Unsupported Finance analysis lens." }
        return buildString {
            appendLine("Finance General Analysis")
            appendLine("Lens: ${input.lensRef}")
            appendLine("Question:")
            appendLine(input.question)
            appendLine("Checked checklist item IDs:")
            input.checkedChecklistItemIds.sorted().forEach { itemId ->
                appendLine("- $itemId")
            }
            appendLine("Output format:")
            appendLine("Return exactly one JSON object with these fields:")
            appendLine("summary: string")
            appendLine("observations: non-empty array of strings")
            appendLine("assumptions: non-empty array of strings")
            appendLine("nextChecks: non-empty array of strings")
            appendLine("evidenceNote: string")
            appendLine("Do not include markdown, trade advice, targets, forecasts, or unsupported facts.")
        }
    }

    private companion object {
        const val GENERAL_ANALYSIS_LENS_REF = "general-analysis"
    }
}

/**
 * Finance-owned adapter over its typed Shared AI port. The local/test-only configuration creates it
 * only when the Shared AI HTTP consumer is explicitly enabled.
 */
class FinanceGeneralAnalysisExecutionAdapter(
    private val contextFactory: FinanceExecutionContextFactory,
    private val sharedAiPort: FinanceSharedAiPort,
    private val queryFormatter: FinanceGeneralAnalysisQueryFormatter = FinanceGeneralAnalysisQueryFormatter(),
    private val interpretationParser: FinanceGeneralAnalysisInterpretationParser =
        FinanceGeneralAnalysisInterpretationParser(),
) : GeneralAnalysisExecutionPort {
    override fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult {
        val businessInput = input.toBusinessInput() ?: return GeneralAnalysisExecutionResult.TechnicalFailure
        val context = try {
            contextFactory.create(input.owner, input.questionId, input.checklistId, input.analysisRequestId)
        } catch (_: Exception) {
            return GeneralAnalysisExecutionResult.TechnicalFailure
        }
        if (!isTrustedFinanceContext(input, context)) return GeneralAnalysisExecutionResult.TechnicalFailure

        val request = FinanceSharedAiRequest(
            context = context,
            skillId = FINANCE_GENERAL_ANALYSIS_SKILL_ID,
            businessInput = businessInput,
            query = try {
                queryFormatter.format(businessInput)
            } catch (_: Exception) {
                return GeneralAnalysisExecutionResult.TechnicalFailure
            },
        )
        val outcome = try {
            sharedAiPort.execute(request)
        } catch (_: Exception) {
            return GeneralAnalysisExecutionResult.TechnicalFailure
        }

        return when (outcome) {
            is FinanceSharedAiOutcome.TechnicalFailure,
            is FinanceSharedAiOutcome.TransportFailure -> GeneralAnalysisExecutionResult.TechnicalFailure

            is FinanceSharedAiOutcome.Success -> {
                if (!isValidResult(context, outcome.evidence, outcome.executionReference)) {
                    GeneralAnalysisExecutionResult.TechnicalFailure
                } else {
                    val candidate = interpretationParser.parse(outcome.interpretation)
                        ?: return GeneralAnalysisExecutionResult.TechnicalFailure
                    GeneralAnalysisExecutionResult.Success(candidate)
                }
            }
        }
    }

    private fun GeneralAnalysisExecutionInput.toBusinessInput(): FinanceGeneralAnalysisBusinessInput? {
        if (question.isBlank() || checkedItemIds.any { it !in CHECKLIST_ITEM_IDS }) return null
        val lensRef = when (lens) {
            GeneralAnalysisLens.GENERAL_ANALYSIS -> GENERAL_ANALYSIS_LENS_REF
        }
        return FinanceGeneralAnalysisBusinessInput(
            question = question,
            checkedChecklistItemIds = checkedItemIds.sorted(),
            lensRef = lensRef,
        )
    }

    private fun isTrustedFinanceContext(
        input: GeneralAnalysisExecutionInput,
        context: FinanceSharedAiRequestContext,
    ): Boolean {
        val expectedResources = setOf(
            "question:${input.questionId}",
            "checklist:${input.checklistId}",
        )
        return context.productReference == FINANCE_PRODUCT_REFERENCE &&
            context.tenantReference.isNotBlank() &&
            context.principalReference == input.owner.value &&
            context.resourceReferences == expectedResources &&
            context.requestReference == input.analysisRequestId.toString() &&
            context.correlationReference.isValidOptionalReference() &&
            context.authorityReference.isValidOptionalReference()
    }

    private fun isValidResult(
        expected: FinanceSharedAiRequestContext,
        evidence: FinanceSharedAiEvidenceEnvelope,
        resultExecutionReference: String,
    ): Boolean {
        val evidenceIds = evidence.items.map { it.evidenceId }
        val returnedContext: FinanceSharedAiExecutionContext = evidence.context
        return resultExecutionReference.isNotBlank() &&
            resultExecutionReference == returnedContext.executionReference &&
            returnedContext.contextVersion == SHARED_AI_CONTEXT_VERSION &&
            returnedContext.productReference == expected.productReference &&
            returnedContext.tenantReference == expected.tenantReference &&
            returnedContext.resourceReferences == expected.resourceReferences &&
            returnedContext.principalReference == expected.principalReference &&
            returnedContext.requestReference == expected.requestReference &&
            returnedContext.correlationReference == expected.correlationReference &&
            returnedContext.authorityReference == expected.authorityReference &&
            evidence.items.isNotEmpty() &&
            evidenceIds.all(String::isNotBlank) &&
            evidenceIds.distinct().size == evidenceIds.size &&
            evidence.items.all { item ->
                item.executionReference == resultExecutionReference &&
                    item.kind in EVIDENCE_KINDS &&
                    item.scopeReference.productReference == returnedContext.productReference &&
                    item.scopeReference.tenantReference == returnedContext.tenantReference &&
                    item.scopeReference.resourceReferences.isNotEmpty() &&
                    returnedContext.resourceReferences.containsAll(item.scopeReference.resourceReferences) &&
                    item.sourceReferences.isNotEmpty() &&
                    item.sourceReferences.none(String::isBlank) &&
                    item.producerReference.isNotBlank() &&
                    item.schemaVersion.isNotBlank()
            }
    }

    private fun String?.isValidOptionalReference(): Boolean = this == null || isNotBlank()

    private companion object {
        const val FINANCE_PRODUCT_REFERENCE = "finance"
        const val FINANCE_GENERAL_ANALYSIS_SKILL_ID = "finance.general-analysis@1"
        const val GENERAL_ANALYSIS_LENS_REF = "general-analysis"
        const val SHARED_AI_CONTEXT_VERSION = "1"
        val CHECKLIST_ITEM_IDS = setOf("known_facts", "assumptions", "missing_context")
        val EVIDENCE_KINDS = setOf("STRUCTURED_PRODUCT_FACT", "RETRIEVED_CONTENT", "MODEL_INTERPRETATION")
    }
}

/** Parses only the existing Finance candidate fields from Shared AI's interpretation string. */
class FinanceGeneralAnalysisInterpretationParser {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(interpretation: String): GeneralAnalysisCandidate? = try {
        if (interpretation.isBlank() || interpretation.length > MAX_INTERPRETATION_LENGTH) return null
        val root = mapper.readTree(interpretation) ?: return null
        if (!root.isObject || root.size() != EXPECTED_CANDIDATE_FIELDS.size) return null

        val summary = textField(root, "summary") ?: return null
        val observations = stringArrayField(root, "observations") ?: return null
        val assumptions = stringArrayField(root, "assumptions") ?: return null
        val nextChecks = stringArrayField(root, "nextChecks") ?: return null
        val evidenceNote = textField(root, "evidenceNote") ?: return null

        if (observations.isEmpty() || assumptions.isEmpty() || nextChecks.isEmpty()) return null
        GeneralAnalysisCandidate(summary, observations, assumptions, nextChecks, evidenceNote)
    } catch (_: Exception) {
        null
    }

    private fun textField(root: JsonNode, name: String): String? =
        root.get(name)?.takeIf { it.isTextual }?.stringValue()

    private fun stringArrayField(root: JsonNode, name: String): List<String>? {
        val values = root.get(name) ?: return null
        if (!values.isArray) return null
        return (0 until values.size()).map { index ->
            val value = values.get(index)
            if (!value.isTextual) return null
            value.stringValue()
        }.takeIf { list -> list.all(String::isNotBlank) }
    }

    private companion object {
        const val MAX_INTERPRETATION_LENGTH = 16_000
        val EXPECTED_CANDIDATE_FIELDS = setOf(
            "summary",
            "observations",
            "assumptions",
            "nextChecks",
            "evidenceNote",
        )
    }
}
