package labs.ranikun.finance.journal.application

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLens
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisPolicyGuard
import labs.ranikun.finance.generalanalysis.application.PolicyClassification
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class JournalReviewUnavailableException : IllegalStateException("Journal review could not be completed.")

/**
 * Executes one selected Journal review in memory. This use case is local/test only and deliberately
 * has no review store, Journal mutation port, controller, or public response DTO.
 */
@Service
@Profile("!prod & !production & (local | test)")
class JournalReviewApplicationService(
    private val currentUserPort: CurrentUserPort,
    private val journalReadApplicationService: JournalReadApplicationService,
    private val policyGuard: GeneralAnalysisPolicyGuard,
    private val executionPort: GeneralAnalysisExecutionPort,
    private val outputValidator: JournalReviewOutputValidator,
) {
    fun review(journalId: UUID): GeneralAnalysisCandidate {
        val owner = try {
            currentUserPort.currentUserId()
        } catch (_: Exception) {
            throw JournalReviewUnavailableException()
        }

        // The existing read path scopes the selected Journal by this already-resolved owner. A
        // foreign or absent Journal follows the same not-found behavior and stops before the guard.
        val journal = journalReadApplicationService.findDetailForOwner(owner, journalId)
        val prompt = try {
            promptFor(journal)
        } catch (_: Exception) {
            throw JournalReviewUnavailableException()
        }
        val classification = try {
            policyGuard.classify(REVIEW_POLICY_INTENT)
        } catch (_: Exception) {
            throw JournalReviewUnavailableException()
        }
        if (classification != PolicyClassification.SAFE_GENERAL) {
            throw JournalReviewUnavailableException()
        }

        val requestId = UUID.randomUUID()
        val executionResult = try {
            executionPort.execute(
                GeneralAnalysisExecutionInput(
                    owner = owner,
                    requestId = requestId,
                    question = prompt,
                    checkedItemIds = emptySet(),
                    lens = GeneralAnalysisLens.JOURNAL_REVIEW,
                    resourceReferences = setOf("journal:$journalId"),
                    requireCorrelation = true,
                    requireUsage = true,
                ),
            )
        } catch (_: Exception) {
            GeneralAnalysisExecutionResult.TechnicalFailure
        }

        val candidate = (executionResult as? GeneralAnalysisExecutionResult.Success)?.candidate
            ?: throw JournalReviewUnavailableException()
        val valid = try {
            outputValidator.isValid(candidate, journal)
        } catch (_: Exception) {
            false
        }
        if (!valid) {
            throw JournalReviewUnavailableException()
        }
        return candidate
    }

    private fun promptFor(journal: JournalDetail): String {
        val fields = when (journal) {
            is JournalDetail.Investment -> linkedMapOf(
                "assetName" to journal.assetName,
                "action" to journal.action,
                "reasoning" to journal.reasoning,
                "emotion" to journal.emotion,
                "occurredLocalAt" to journal.occurredLocalAt.toString(),
                "timeZone" to journal.timeZone,
            )

            is JournalDetail.Study -> linkedMapOf(
                "title" to journal.title,
                "keyContent" to journal.keyContent,
                "openQuestions" to journal.openQuestions,
                "occurredLocalAt" to journal.occurredLocalAt.toString(),
                "timeZone" to journal.timeZone,
            )
        }
        val encodedFields = JOURNAL_FIELDS_MAPPER.writeValueAsString(fields)
        return "What facts are present in this selected Journal? Journal field values (JSON): $encodedFields"
    }

    private companion object {
        const val REVIEW_POLICY_INTENT = "What facts are present in this selected Journal?"
        val JOURNAL_FIELDS_MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
