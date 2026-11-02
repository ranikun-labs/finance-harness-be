package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.FinanceExecutionContextFactory
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiPort
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiRequestContext
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.net.URI
import java.util.UUID

/** Opt-in Finance consumer wiring; it is unavailable on production profiles. */
@Configuration(proxyBeanMethods = false)
@Profile("!prod & !production & (local | test)")
@ConditionalOnProperty(prefix = "finance.shared-ai", name = ["enabled"], havingValue = "true")
class FinanceSharedAiHttpConfiguration {
    @Bean
    fun financeSharedAiPort(
        @Value("\${finance.shared-ai.base-url:}") baseUrl: String,
    ): FinanceSharedAiPort {
        require(baseUrl.isNotBlank()) { "Finance Shared AI base URL must be configured when enabled." }
        return FinanceSharedAiHttpClient(URI.create(baseUrl))
    }

    @Bean
    fun financeExecutionContextFactory(
        @Value("\${finance.shared-ai.tenant-reference:}") tenantReference: String,
    ): FinanceExecutionContextFactory = LocalFinanceExecutionContextFactory(tenantReference)

    @Bean
    fun financeGeneralAnalysisExecutionPort(
        contextFactory: FinanceExecutionContextFactory,
        financeSharedAiPort: FinanceSharedAiPort,
    ): GeneralAnalysisExecutionPort = FinanceGeneralAnalysisExecutionAdapter(contextFactory, financeSharedAiPort)
}

/** Uses only explicit local/test configuration for tenant scope; it never derives tenant from identity. */
internal class LocalFinanceExecutionContextFactory(
    tenantReference: String,
) : FinanceExecutionContextFactory {
    private val trustedTenantReference = tenantReference.also {
        require(it.isNotBlank()) { "Finance Shared AI tenant reference must be configured when enabled." }
    }

    override fun create(
        owner: IdentityUserId,
        questionId: UUID,
        checklistId: UUID,
        analysisRequestId: UUID,
    ) = FinanceSharedAiRequestContext(
        productReference = FINANCE_PRODUCT_REFERENCE,
        tenantReference = trustedTenantReference,
        resourceReferences = setOf("question:$questionId", "checklist:$checklistId"),
        principalReference = owner.value,
        requestReference = analysisRequestId.toString(),
        correlationReference = null,
        authorityReference = null,
    )

    private companion object {
        const val FINANCE_PRODUCT_REFERENCE = "finance"
    }
}
