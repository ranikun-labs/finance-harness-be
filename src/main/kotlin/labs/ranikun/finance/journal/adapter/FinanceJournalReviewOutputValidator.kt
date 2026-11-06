package labs.ranikun.finance.journal.adapter

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.journal.application.JournalDetail
import labs.ranikun.finance.journal.application.JournalReviewOutputValidator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Review-only gate. Factual output is limited to exact field/value templates derived from the
 * selected Journal; the existing Finance validator then checks the complete candidate.
 */
@Component
@Profile("!prod & !production & (local | test)")
class FinanceJournalReviewOutputValidator(
    private val generalAnalysisOutputValidator: GeneralAnalysisOutputValidator,
) : JournalReviewOutputValidator {
    override fun isValid(candidate: GeneralAnalysisCandidate, journal: JournalDetail): Boolean {
        val recordedFacts = recordedFactStatements(journal)
        if (candidate.summary !in recordedFacts) return false
        if (candidate.observations != listOf(expectedUserReasoning(journal))) return false
        if (candidate.assumptions != listOf(EXPECTED_ASSUMPTION)) return false
        if (candidate.nextChecks != EXPECTED_NEXT_CHECKS) return false
        if (candidate.evidenceNote != expectedEvidenceNote(journal)) return false

        val allText = listOf(candidate.summary, candidate.evidenceNote) +
            candidate.observations + candidate.assumptions + candidate.nextChecks
        if (allText.any { it.isBlank() || it.length > MAX_OUTPUT_FIELD_LENGTH }) return false

        val candidateWithBoundSourceRedacted = candidateWithBoundSourceRedacted(candidate, journal)
        if (!generalAnalysisOutputValidator.isValid(candidateWithBoundSourceRedacted)) return false

        val generatedText = listOf(
            candidateWithBoundSourceRedacted.summary,
            candidateWithBoundSourceRedacted.evidenceNote,
        ) + candidateWithBoundSourceRedacted.observations + candidateWithBoundSourceRedacted.assumptions +
            candidateWithBoundSourceRedacted.nextChecks
        return generatedText.none { UNSAFE_REVIEW_JUDGMENT_PATTERN.containsMatchIn(it) }
    }

    private fun candidateWithBoundSourceRedacted(
        candidate: GeneralAnalysisCandidate,
        journal: JournalDetail,
    ): GeneralAnalysisCandidate {
        var validatedCandidate = candidate
        if (candidate.summary in recordedFactStatements(journal)) {
            // The exact statement is field-bound above. Redact only that verified source text
            // when applying generated-content policy checks; length and binding were checked first.
            validatedCandidate = validatedCandidate.copy(summary = VERIFIED_FACT_VALIDATION_PLACEHOLDER)
        }

        if (candidate.observations == listOf(expectedUserReasoning(journal))) {
            // The caller first checks that this is the exact JSON-bound, user-labeled Journal
            // value. Only that verified source is redacted; generated sections remain checked.
            validatedCandidate = validatedCandidate.copy(
                observations = listOf(VERIFIED_REASONING_VALIDATION_PLACEHOLDER),
            )
        }
        return validatedCandidate
    }

    private fun recordedFactStatements(journal: JournalDetail): List<String> =
        journalFieldValues(journal).map { (field, value) -> recordedFact(field, value) }

    private fun expectedUserReasoning(journal: JournalDetail): String = when (journal) {
        is JournalDetail.Investment -> userReasoningFact("reasoning", journal.reasoning)
        is JournalDetail.Study -> userReasoningFact("keyContent", journal.keyContent)
    }

    private fun expectedEvidenceNote(journal: JournalDetail): String =
        "Evidence: Journal fields ${journalFieldValues(journal).joinToString(", ") { it.first }}; Journal is the only source."

    private fun journalFieldValues(journal: JournalDetail): List<Pair<String, Any?>> = when (journal) {
        is JournalDetail.Investment -> listOf(
            "assetName" to journal.assetName,
            "action" to journal.action,
            "reasoning" to journal.reasoning,
            "emotion" to journal.emotion,
            "occurredLocalAt" to journal.occurredLocalAt.toString(),
            "timeZone" to journal.timeZone,
        )

        is JournalDetail.Study -> listOf(
            "title" to journal.title,
            "keyContent" to journal.keyContent,
            "openQuestions" to journal.openQuestions,
            "occurredLocalAt" to journal.occurredLocalAt.toString(),
            "timeZone" to journal.timeZone,
        )
    }

    private fun recordedFact(field: String, value: Any?): String =
        "Recorded facts: $field field equals ${VALUE_MAPPER.writeValueAsString(value)}."

    private fun userReasoningFact(field: String, value: String): String =
        "User-provided reasoning: $field field equals ${VALUE_MAPPER.writeValueAsString(value)}."

    private companion object {
        const val MAX_OUTPUT_FIELD_LENGTH = 2_000
        val VALUE_MAPPER: JsonMapper = JsonMapper.builder().build()
        const val VERIFIED_FACT_VALIDATION_PLACEHOLDER =
            "Recorded facts: an exact selected Journal field value is present."
        const val VERIFIED_REASONING_VALIDATION_PLACEHOLDER =
            "User-provided reasoning: the selected Journal reasoning field is present."
        const val EXPECTED_ASSUMPTION =
            "Possible assumptions: The Journal may omit context that could affect how its fields are understood."
        val EXPECTED_NEXT_CHECKS = listOf(
            "Missing evidence/checks: No separately cited external source is attached to this Journal.",
            "Neutral next question: Which Journal field would you like to clarify?",
        )
        val UNSAFE_REVIEW_JUDGMENT_PATTERN = Regex(
            "\\b(?:you|the user)\\s+(?:were|was)\\s+(?:right|wrong)\\b|" +
                "\\b(?:good|bad|wise|unwise|correct|incorrect|smart|poor)\\s+(?:past\\s+)?(?:decision|choice|move)\\b|" +
                "\\b(?:decision|choice|move|action|call)\\s+(?:is|was|were|seems?|appears?)\\s+(?:right|wrong|good|bad|wise|unwise|correct|incorrect|smart|poor)\\b|" +
                "\\b(?:you|the user)\\s+(?:made|took)\\s+(?:the\\s+)?(?:right|wrong|good|bad|wise|unwise|correct|incorrect|smart|poor)\\s+(?:decision|choice|move|action|call)\\b|" +
                "\\bshould\\s+have\\b|\\b(?:you|the user)\\s+(?:should|must|need to|ought to)\\b|" +
                "\\b(?:you|the user)\\s+(?:seems?|appears?|tends? to|are|were|is|was)\\s+(?:risk[- ]?(?:averse|seeking)|confident|anxious|impulsive|patient|careless|cautious|fearful|greedy|emotional)\\b|" +
                "\\b(?:your|the user's)\\s+(?:risk tolerance|personality|behavior(?: pattern)?|financial habits?|profile)\\b|" +
                "\\b(?:risk[- ]?(?:averse|seeking|tolerance)|personality|user profile|behavior pattern|financial habits?)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
