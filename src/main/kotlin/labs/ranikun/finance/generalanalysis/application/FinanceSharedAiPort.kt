package labs.ranikun.finance.generalanalysis.application

import labs.ranikun.finance.identity.application.IdentityUserId
import java.time.Instant
import java.util.UUID

/** Finance-owned skill input. Checklist IDs are sorted before formatting the Shared AI query. */
data class FinanceGeneralAnalysisBusinessInput(
    val question: String,
    val checkedChecklistItemIds: List<String>,
    val lensRef: String,
)

/** Finance-owned product context seed; Shared AI supplies executionReference and contextVersion. */
data class FinanceSharedAiRequestContext(
    val productReference: String,
    val tenantReference: String,
    val resourceReferences: Set<String>,
    val principalReference: String,
    val requestReference: String,
    val correlationReference: String?,
    val authorityReference: String?,
)

/** Shared-AI-issued context returned with evidence; Finance validates it before parsing results. */
data class FinanceSharedAiExecutionContext(
    val productReference: String,
    val tenantReference: String,
    val resourceReferences: Set<String>,
    val principalReference: String,
    val requestReference: String,
    val executionReference: String,
    val correlationReference: String?,
    val contextVersion: String,
    val authorityReference: String?,
)

data class FinanceSharedAiScope(
    val productReference: String,
    val tenantReference: String,
    val resourceReferences: Set<String>,
)

/**
 * Finance's typed invocation. `query` is the deterministic plain-text representation passed through
 * the Shared AI query field; it is not the JSON test fixture.
 */
data class FinanceSharedAiRequest(
    val context: FinanceSharedAiRequestContext,
    val skillId: String,
    val businessInput: FinanceGeneralAnalysisBusinessInput,
    val query: String,
)

data class FinanceSharedAiEvidenceItem(
    val evidenceId: String,
    val kind: String,
    val sourceReferences: Set<String>,
    val producerReference: String,
    val observedAt: Instant,
    val executionReference: String,
    val scopeReference: FinanceSharedAiScope,
    val schemaVersion: String,
)

data class FinanceSharedAiEvidenceEnvelope(
    val context: FinanceSharedAiExecutionContext,
    val items: List<FinanceSharedAiEvidenceItem>,
)

enum class FinanceSharedAiFailureCategory {
    UNSUPPORTED_SKILL,
    PRODUCT_TOOL_FAILURE,
    RETRIEVER_FAILURE,
    MODEL_FAILURE,
    CORRELATION_FAILURE,
}

enum class FinanceSharedAiTransportFailure {
    INVALID_REQUEST,
    NETWORK,
    TIMEOUT,
    HTTP_STATUS,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    INTERRUPTED,
}

data class FinanceSharedAiUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
)

sealed interface FinanceSharedAiOutcome {
    data class Success(
        val executionReference: String,
        val interpretation: String,
        val evidence: FinanceSharedAiEvidenceEnvelope,
        val warnings: List<String> = emptyList(),
        val usage: FinanceSharedAiUsage? = null,
    ) : FinanceSharedAiOutcome

    data class TechnicalFailure(
        val executionReference: String,
        val category: FinanceSharedAiFailureCategory,
    ) : FinanceSharedAiOutcome

    data class TransportFailure(
        val category: FinanceSharedAiTransportFailure,
    ) : FinanceSharedAiOutcome
}

/** Finance-owned seam for the opt-in local/test Shared AI consumer. */
fun interface FinanceSharedAiPort {
    fun execute(request: FinanceSharedAiRequest): FinanceSharedAiOutcome
}

/**
 * Supplies context from trusted Finance identity, verified resources, and an explicit local/test
 * tenant property. Production implementations are not registered in this slice.
 */
fun interface FinanceExecutionContextFactory {
    fun create(
        owner: IdentityUserId,
        questionId: UUID,
        checklistId: UUID,
        analysisRequestId: UUID,
    ): FinanceSharedAiRequestContext
}
